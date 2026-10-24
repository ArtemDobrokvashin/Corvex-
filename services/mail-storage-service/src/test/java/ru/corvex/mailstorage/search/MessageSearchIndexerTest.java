package ru.corvex.mailstorage.search;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.annotations.Document;
import ru.corvex.mailstorage.mime.MimeMailParser;
import ru.corvex.mailstorage.service.MailDeliveryService;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MessageSearchIndexerTest {

    @Test
    void buildsDocumentWithRecipientsMailboxFallbackAndAttachmentFlag() throws Exception {
        MessageSearchRepository repository = mock(MessageSearchRepository.class);
        MessageSearchIndexer indexer = new MessageSearchIndexer(repository);
        MailDeliveryService.DeliveryResult result = result(
                List.of(address("to@company.local")),
                List.of(address("cc@company.local")),
                List.of(new MimeMailParser.ParsedAttachment("a.txt", "text/plain", 1, "hash")));

        indexer.index(result);

        ArgumentCaptor<MessageSearchDocument> captor = ArgumentCaptor.forClass(MessageSearchDocument.class);
        verify(repository).save(captor.capture());
        MessageSearchDocument doc = captor.getValue();
        assertEquals(result.messageId().toString(), field(doc, "id"));
        assertEquals(result.ownerId().toString(), field(doc, "ownerId"));
        assertEquals(result.folderId().toString(), field(doc, "folderId"));
        assertEquals(19L, field(doc, "uid"));
        assertEquals(List.of("to@company.local", "cc@company.local", "owner@company.local"),
                field(doc, "recipients"));
        assertEquals(true, field(doc, "hasAttachments"));
    }

    @Test
    void doesNotDuplicateMailboxRecipient() throws Exception {
        MessageSearchRepository repository = mock(MessageSearchRepository.class);
        MessageSearchIndexer indexer = new MessageSearchIndexer(repository);
        MailDeliveryService.DeliveryResult result = result(
                List.of(address("owner@company.local")), List.of(), List.of());

        indexer.index(result);

        ArgumentCaptor<MessageSearchDocument> captor = ArgumentCaptor.forClass(MessageSearchDocument.class);
        verify(repository).save(captor.capture());
        assertEquals(List.of("owner@company.local"), field(captor.getValue(), "recipients"));
        assertEquals(false, field(captor.getValue(), "hasAttachments"));
    }

    @Test
    void elasticFailureDoesNotFailDelivery() {
        MessageSearchRepository repository = mock(MessageSearchRepository.class);
        doThrow(new IllegalStateException("elastic down")).when(repository).save(org.mockito.ArgumentMatchers.any());
        MessageSearchIndexer indexer = new MessageSearchIndexer(repository);

        assertDoesNotThrow(() -> indexer.index(result(List.of(), List.of(), List.of())));
    }

    @Test
    void documentAnnotationAndDefaultConstructorKeepIndexContract() throws Exception {
        Document document = MessageSearchDocument.class.getAnnotation(Document.class);
        assertNotNull(document);
        assertEquals("corvex-messages", document.indexName());
        assertNotNull(new MessageSearchDocument());
    }

    private MailDeliveryService.DeliveryResult result(
            List<MimeMailParser.ParsedAddress> to,
            List<MimeMailParser.ParsedAddress> cc,
            List<MimeMailParser.ParsedAttachment> attachments) {
        MimeMailParser.ParsedMail parsed = new MimeMailParser.ParsedMail(
                "<m@x>", "sender@partner.test", "Subject", "Snippet", "Body", null,
                Instant.parse("2026-01-02T03:04:05Z"), to, cc, attachments);
        return new MailDeliveryService.DeliveryResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 19, "owner@company.local", parsed);
    }

    private static MimeMailParser.ParsedAddress address(String email) {
        return new MimeMailParser.ParsedAddress(email, null);
    }

    private static Object field(MessageSearchDocument doc, String name) throws Exception {
        Field field = MessageSearchDocument.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(doc);
    }
}
