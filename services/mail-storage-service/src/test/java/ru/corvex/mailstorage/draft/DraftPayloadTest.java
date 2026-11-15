package ru.corvex.mailstorage.draft;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DraftPayloadTest {
    private DraftPayload draft(List<DraftPayload.Attachment> attachments) {
        return new DraftPayload("one@example.test", "", "subject", "body", "<p>body</p>", null, attachments);
    }
    @Test void acceptsEmptyFieldsAndValidAttachment() {
        assertDoesNotThrow(() -> draft(null).validate());
        assertDoesNotThrow(() -> draft(List.of(new DraftPayload.Attachment("тест.txt", "text/plain", "aGVsbG8="))).validate());
    }
    @Test void rejectsInvalidOrExcessiveAttachments() {
        assertThrows(IllegalArgumentException.class, () -> draft(List.of(new DraftPayload.Attachment("test", "text/plain", "!!!!"))).validate());
        assertThrows(IllegalArgumentException.class, () -> draft(List.of(new DraftPayload.Attachment("bad\nname", "text/plain", ""))).validate());
        assertThrows(IllegalArgumentException.class, () -> draft(List.of(new DraftPayload.Attachment("big", "text/plain", "a".repeat(5_000_000)))).validate());
        assertThrows(IllegalArgumentException.class, () -> draft(java.util.Collections.nCopies(11, new DraftPayload.Attachment("a", "text/plain", ""))).validate());
    }
    @Test void boundsTextInUtf8Bytes() {
        assertThrows(IllegalArgumentException.class, () -> new DraftPayload("", "", "a".repeat(999), "", null, null, List.of()).validate());
        assertThrows(IllegalArgumentException.class, () -> new DraftPayload("", "", "", "я".repeat(250_001), null, null, List.of()).validate());
    }
}
