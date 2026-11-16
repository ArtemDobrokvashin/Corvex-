package ru.corvex.mailstorage.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Публичные DTO адресной книги (gateway: /api/v1/mail/contacts). */
public final class ContactDtos {

    private ContactDtos() {
    }

    public record ContactView(
            UUID id,
            String displayName,
            String email,
            String phone,
            String organization,
            String jobTitle,
            String note,
            String groupName,
            String source,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record ContactRequest(
            String displayName,
            String email,
            String phone,
            String organization,
            String jobTitle,
            String note,
            String groupName) {
    }

    public record ImportIssueView(int line, String value, String reason) {
    }

    /**
     * Итог импорта. `skipped` — строки без адреса и контакты, которые уже были без изменений;
     * `truncated` — импорт остановлен на лимите адресной книги, остаток файла не обработан.
     */
    public record ImportResult(
            int total,
            int created,
            int updated,
            int skipped,
            boolean truncated,
            List<ImportIssueView> issues) {
    }
}
