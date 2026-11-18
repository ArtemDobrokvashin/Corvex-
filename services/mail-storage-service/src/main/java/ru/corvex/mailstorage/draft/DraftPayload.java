package ru.corvex.mailstorage.draft;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Validation for private webmail drafts; used by WebmailDraftController (owner-scoped, revision-checked). */
public record DraftPayload(String to, String cc, String subject, String bodyText, String bodyHtml,
                           UUID externalAccountId, List<Attachment> attachments) {
    private static final int MAX_TEXT = 500_000;
    private static final int MAX_RECIPIENTS_TEXT = 16_000;
    private static final int MAX_FILES = 10;
    private static final int MAX_FILE_BYTES = 3 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 10 * 1024 * 1024;
    public record Attachment(String fileName, String contentType, String contentBase64) { }

    public void validate() {
        bounded(to, MAX_RECIPIENTS_TEXT); bounded(cc, MAX_RECIPIENTS_TEXT);
        bounded(subject, 998); bounded(bodyText, MAX_TEXT); bounded(bodyHtml, MAX_TEXT);
        if (attachments == null) return;
        if (attachments.size() > MAX_FILES) throw new IllegalArgumentException("Too many attachments");
        long total = 0;
        for (Attachment file : attachments) {
            if (file == null || file.fileName() == null || file.fileName().isBlank() || file.fileName().length() > 512
                    || file.fileName().chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("Invalid attachment name");
            bounded(file.contentType(), 255);
            String base64 = file.contentBase64();
            if (base64 == null || base64.length() > 4L * ((MAX_FILE_BYTES + 2L) / 3L)) throw new IllegalArgumentException("Attachment too large");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(base64); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid base64 attachment", e); }
            if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Attachment too large");
            total += bytes.length;
            if (total > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Attachments too large");
        }
    }

    private static void bounded(String value, int maxBytes) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new IllegalArgumentException("Draft field too large");
    }
}
