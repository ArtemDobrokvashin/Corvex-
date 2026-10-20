package ru.corvex.chatfile.api;

import ru.corvex.chatfile.domain.StoredChatFile;

import java.time.Instant;
import java.util.UUID;

/** Публичные контракты /api/v1/files, синхронизированные с frontend/src/api/chatApi.ts. */
public final class ChatFileDtos {

    private ChatFileDtos() {
    }

    public record ChatFileUploadView(
            String fileRef,
            UUID roomId,
            UUID ownerId,
            String fileName,
            String contentType,
            long sizeBytes,
            String sha256,
            Instant createdAt) {

        public static ChatFileUploadView from(StoredChatFile file) {
            return new ChatFileUploadView(
                    file.fileRef(),
                    file.roomId(),
                    file.ownerId(),
                    file.fileName(),
                    file.contentType(),
                    file.sizeBytes(),
                    file.sha256(),
                    file.createdAt());
        }
    }
}
