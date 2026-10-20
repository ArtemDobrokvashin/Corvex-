package ru.corvex.auth.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.corvex.auth.domain.LoginAttempt;
import ru.corvex.auth.repository.LoginAttemptRepository;

/**
 * Запись попыток входа в отдельной транзакции (REQUIRES_NEW): неудачная попытка
 * должна сохраниться, даже когда основной сценарий входа завершается исключением
 * и его транзакция откатывается.
 */
@Component
public class LoginAttemptRecorder {

    private final LoginAttemptRepository attempts;

    public LoginAttemptRecorder(LoginAttemptRepository attempts) {
        this.attempts = attempts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String username, String ip, boolean success, String reason) {
        attempts.save(new LoginAttempt(username, ip, success, reason));
    }
}
