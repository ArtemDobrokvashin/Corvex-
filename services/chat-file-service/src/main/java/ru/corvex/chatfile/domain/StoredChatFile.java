package ru.corvex.chatfile.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Метаданные одного опубликованного вложения. Содержимое хранится отдельно в CAS по SHA-256,
 * поэтому одинаковые файлы в разных комнатах не дублируют байты, а доступ проверяется по этой записи.
 */
public record StoredChatFile(
        UUID id,
        UUID roomId,
        UUID ownerId,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        AvStatus avStatus,
        String avSignature,
        Instant createdAt) {

    public enum AvStatus {
        CLEAN,
        ERROR,
        INFECTED
    }

    public String fileRef() {
        return id.toString();
    }
}
