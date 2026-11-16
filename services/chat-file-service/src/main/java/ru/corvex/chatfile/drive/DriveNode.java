package ru.corvex.chatfile.drive;

import java.time.Instant;
import java.util.UUID;

/**
 * Узел личного диска: папка или файл.
 *
 * <p>Метаданные лежат отдельным JSON-файлом рядом с blob'ами chat-file-service. Это тот же
 * принцип, что и у вложений чата: сначала атомарно публикуется blob, затем атомарно
 * заменяется JSON. Диск не использует общую БД, поэтому один и тот же каталог одинаково
 * попадает в backup и не требует миграции схемы на уже развёрнутом стенде.</p>
 *
 * <p>{@code parentId == null} — корень диска пользователя. {@code trashedAt != null} —
 * узел в корзине; содержимое остаётся на диске до явной очистки.</p>
 */
public record DriveNode(
        UUID id,
        UUID ownerId,
        UUID parentId,
        Kind kind,
        String name,
        long sizeBytes,
        String contentType,
        String sha256,
        String avStatus,
        Instant createdAt,
        Instant updatedAt,
        Instant trashedAt) {

    public enum Kind {FILE, FOLDER}

    public boolean isFolder() {
        return kind == Kind.FOLDER;
    }

    public boolean isTrashed() {
        return trashedAt != null;
    }

    public DriveNode trashed(Instant at) {
        return new DriveNode(id, ownerId, parentId, kind, name, sizeBytes, contentType,
                sha256, avStatus, createdAt, at, at);
    }

    public DriveNode restored() {
        return new DriveNode(id, ownerId, parentId, kind, name, sizeBytes, contentType,
                sha256, avStatus, createdAt, Instant.now(), null);
    }

    public DriveNode renamed(String newName) {
        return new DriveNode(id, ownerId, parentId, kind, newName, sizeBytes, contentType,
                sha256, avStatus, createdAt, Instant.now(), trashedAt);
    }

    public DriveNode movedTo(UUID newParentId) {
        return new DriveNode(id, ownerId, newParentId, kind, name, sizeBytes, contentType,
                sha256, avStatus, createdAt, Instant.now(), trashedAt);
    }
}
