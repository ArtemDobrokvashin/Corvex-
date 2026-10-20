package ru.corvex.auth.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import ru.corvex.auth.domain.User;
import ru.corvex.auth.repository.UserRepository;
import ru.corvex.auth.security.PasswordPolicy;

import java.security.SecureRandom;

/**
 * Создание суперадминистратора при первом старте (пустая база пользователей).
 * Пароль: CORVEX_BOOTSTRAP_ADMIN_PASSWORD, а если не задан — генерируется и
 * ОДИН РАЗ печатается в лог (искать: docker compose logs auth-service | grep BOOTSTRAP).
 * Группа SUPER_ADMIN назначается admin-service при его старте (владелец RBAC — он),
 * вход потребует сменить пароль (mustChangePassword).
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String email;

    public BootstrapAdminInitializer(UserRepository users,
                                     PasswordEncoder passwordEncoder,
                                     @Value("${corvex.bootstrap.admin-username:admin}") String username,
                                     @Value("${corvex.bootstrap.admin-password:}") String password,
                                     @Value("${corvex.bootstrap.admin-email:admin@company.local}") String email) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.email = email;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        boolean generated = password == null || password.isBlank();
        String effectivePassword = generated ? generatePassword() : password;
        String policyError = PasswordPolicy.validate(effectivePassword);
        if (policyError != null) {
            log.error("BOOTSTRAP ABORTED: CORVEX_BOOTSTRAP_ADMIN_PASSWORD не проходит политику паролей: {}", policyError);
            return;
        }
        // Пустая переменная окружения в compose перекрывает default плейсхолдера пустой строкой
        String effectiveEmail = email == null || email.isBlank() ? "admin@company.local" : email;
        User admin = new User();
        admin.setUsername(username.trim().toLowerCase());
        admin.setEmail(effectiveEmail.trim().toLowerCase());
        admin.setDisplayName("Супер администратор");
        admin.setPasswordHash(passwordEncoder.encode(effectivePassword));
        admin.setSource(User.Source.LOCAL);
        admin.setStatus(User.Status.ACTIVE);
        admin.setMustChangePassword(true);
        users.save(admin);

        if (generated) {
            log.warn("""

                    ================= CORVEX BOOTSTRAP SUPERADMIN CREATED =================
                     Username: {}
                     Password (generated, shown ONCE): {}
                     Sign in and change it immediately — the UI will enforce the change.
                     Group SUPER_ADMIN is assigned by admin-service on its startup.
                    =======================================================================""",
                    admin.getUsername(), effectivePassword);
        } else {
            log.info("CORVEX BOOTSTRAP SUPERADMIN CREATED: username={} (password from CORVEX_BOOTSTRAP_ADMIN_PASSWORD)",
                    admin.getUsername());
        }
    }

    private static String generatePassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder("Cv9"); // гарантия букв+цифры для политики
        for (int i = 0; i < 17; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
