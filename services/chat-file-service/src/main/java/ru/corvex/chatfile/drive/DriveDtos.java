package ru.corvex.chatfile.drive;

import java.time.Instant;
import java.util.UUID;

/** Контракты диска: только то, что нужно UI, без внутренних путей хранилища и хешей blob'ов. */
public final class DriveDtos {

    private DriveDtos() {
    }

    public record NodeView(
            UUID id,
            UUID parentId,
            String kind,
            String name,
            long sizeBytes,
            String contentType,
            Instant createdAt,
            Instant updatedAt,
            Instant trashedAt) {

        public static NodeView from(DriveNode node) {
            return new NodeView(node.id(), node.parentId(), node.kind().name(), node.name(),
                    node.sizeBytes(), node.contentType(), node.createdAt(), node.updatedAt(),
                    node.trashedAt());
        }
    }

    public record FolderRequest(UUID parentId, String name) {
    }

    public record RenameRequest(String name) {
    }

    public record MoveRequest(UUID parentId) {
    }

    public record ShareRequest(Integer expiresInDays) {
    }

    public record ShareView(UUID id, UUID nodeId, Instant createdAt, Instant expiresAt, Instant revokedAt) {

        public static ShareView from(DriveShare share) {
            return new ShareView(share.id(), share.nodeId(), share.createdAt(),
                    share.expiresAt(), share.revokedAt());
        }
    }

    /**
     * Токен возвращается один раз, в момент создания ссылки: сервер хранит только хеш.
     * {@code path} — относительный адрес, клиент добавляет свой origin.
     */
    public record ShareCreatedView(ShareView share, String token, String path) {
    }

    public record UsageView(long usedBytes, long quotaBytes) {

        public static UsageView from(DriveService.Usage usage) {
            return new UsageView(usage.usedBytes(), usage.quotaBytes());
        }
    }
}
