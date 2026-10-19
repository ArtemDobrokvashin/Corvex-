package ru.corvex.admin.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.corvex.admin.client.AuthServiceClient;
import ru.corvex.admin.service.AdminGroupService;

/**
 * Парная часть бутстрапа первого старта: auth-service создаёт пользователя
 * суперадминистратора, а этот компонент (владелец RBAC) добавляет его в группу
 * SUPER_ADMIN. Работает в фоновом потоке с ретраями: порядок старта контейнеров
 * и гонка с созданием пользователя не должны ломать первый запуск.
 * Повторные старты безопасны (идемпотентная вставка ON CONFLICT DO NOTHING).
 */
@Component
public class BootstrapSuperAdminAssignment implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapSuperAdminAssignment.class);
    private static final int MAX_ATTEMPTS = 30;
    private static final long DELAY_MS = 5_000;

    private final AuthServiceClient authClient;
    private final AdminGroupService groupService;
    private final String bootstrapUsername;

    public BootstrapSuperAdminAssignment(AuthServiceClient authClient,
                                         AdminGroupService groupService,
                                         @Value("${corvex.bootstrap.admin-username:admin}") String bootstrapUsername) {
        this.authClient = authClient;
        this.groupService = groupService;
        this.bootstrapUsername = bootstrapUsername;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread thread = new Thread(this::assignWithRetries, "bootstrap-superadmin");
        thread.setDaemon(true);
        thread.start();
    }

    private void assignWithRetries() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                var user = authClient.findByUsername(bootstrapUsername);
                if (user.isPresent()) {
                    if (groupService.ensureSuperAdmin(user.get().id())) {
                        log.info("Bootstrap: user '{}' is in SUPER_ADMIN group", bootstrapUsername);
                        return;
                    }
                    log.warn("Bootstrap: SUPER_ADMIN group is missing in admin_db (Flyway seed not applied?)");
                    return;
                }
            } catch (Exception e) {
                log.debug("Bootstrap superadmin attempt {} failed: {}", attempt, e.getMessage());
            }
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Bootstrap: could not assign SUPER_ADMIN to '{}' after {} attempts "
                + "(user may be created later or with a different name)", bootstrapUsername, MAX_ATTEMPTS);
    }
}
