package ru.corvex.auth.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.corvex.auth.repository.RefreshSessionRepository;

import java.time.Instant;
import java.util.UUID;

/**
 * Отзыв refresh-сессий в отдельной транзакции: отзыв при обнаружении кражи токена,
 * блокировке пользователя и т.п. обязан примениться, даже если вызывающий сценарий
 * затем бросает исключение и откатывает свою транзакцию.
 */
@Component
public class SessionRevoker {

    private final RefreshSessionRepository sessions;

    public SessionRevoker(RefreshSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAll(UUID userId) {
        return sessions.revokeAllByUserId(userId, Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllExcept(UUID userId, String keepTokenHash) {
        return sessions.revokeAllExcept(userId, keepTokenHash == null ? "" : keepTokenHash, Instant.now());
    }
}
