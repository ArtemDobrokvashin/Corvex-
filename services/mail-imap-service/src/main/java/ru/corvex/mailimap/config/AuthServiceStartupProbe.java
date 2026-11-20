package ru.corvex.mailimap.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.corvex.mailimap.client.ImapAuthClient;

/**
 * Проверяет связку с auth-service при старте, до первого IMAP-входа.
 *
 * <p>James на любой сбой аутентификации отвечает клиенту либо «Invalid login/password»,
 * либо «processing failed», и по этому тексту невозможно понять, дело в пароле, в
 * токене или в URL. Поэтому сервис сам, сразу после старта, проходит те же три вызова,
 * что и LOGIN — без пароля пользователя — и пишет результат в лог. Если что-то не так,
 * это видно в {@code docker compose logs mail-imap-service} за секунды.
 */
@Component
public class AuthServiceStartupProbe implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceStartupProbe.class);

    private final ImapAuthClient auth;
    private final String probeEmail;

    public AuthServiceStartupProbe(
            ImapAuthClient auth,
            @Value("${corvex.auth.self-check-email:${CORVEX_AUTH_SELF_CHECK_EMAIL:admin@company.local}}")
            String probeEmail) {
        this.auth = auth;
        this.probeEmail = probeEmail;
    }

    @Override
    public void run(ApplicationArguments args) {
        ImapAuthClient.SelfCheck result = auth.selfCheck(probeEmail);
        if (result.ok()) {
            log.info("auth-service self-check: {}", result.message());
        } else {
            // WARN, а не ERROR: сервис продолжает работать, но IMAP-вход не пройдёт
            // ни с каким паролем, пока это не исправлено.
            log.warn("auth-service self-check ПРОВАЛЕН: {}. IMAP-вход не заработает ни с "
                    + "каким паролем, пока это не исправлено", result.message());
        }
    }
}
