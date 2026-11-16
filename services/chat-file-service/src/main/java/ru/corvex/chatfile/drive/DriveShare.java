package ru.corvex.chatfile.drive;

import java.time.Instant;
import java.util.UUID;

/**
 * Ссылка на файл диска «как в Google Drive».
 *
 * <p>Ссылка приватна по умолчанию: пока владелец не создал её, файл не видит никто, кроме
 * владельца. Токен показывается пользователю один раз и в хранилище не попадает — сохраняется
 * только SHA-256 токена, поэтому копия каталога метаданных не даёт рабочих ссылок.
 * Отзыв — {@code revokedAt != null}: проверка идёт на каждый запрос, поэтому отзыв действует
 * немедленно и не зависит от кэшей клиента.</p>
 */
public record DriveShare(
        UUID id,
        UUID nodeId,
        UUID ownerId,
        String tokenHash,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt) {

    public boolean isActive(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
