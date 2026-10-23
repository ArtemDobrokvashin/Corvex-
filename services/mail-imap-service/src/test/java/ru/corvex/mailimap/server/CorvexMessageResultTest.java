package ru.corvex.mailimap.server;

import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.ModSeq;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.ComposedMessageId;
import org.apache.james.mailbox.model.Content;
import org.apache.james.mailbox.model.Header;
import org.apache.james.mailbox.model.MessageMetaData;
import org.apache.james.mailbox.model.MessageResult;
import org.apache.james.mailbox.model.MimeDescriptor;
import org.apache.james.mailbox.model.MimePath;
import org.apache.james.mailbox.model.ThreadId;
import org.junit.jupiter.api.Test;
import ru.corvex.mailimap.client.ImapStorageClient;

import javax.mail.Flags;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ленивый FETCH-контракт поверх raw .eml: root, multipart, BODY[1] и вложенный message/rfc822. */
class CorvexMessageResultTest {

    private static final UUID FOLDER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MESSAGE_UUID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant INTERNAL_DATE = Instant.parse("2026-07-14T07:30:00Z");

    private static final String MULTIPART_TEXT = String.join("\r\n",
            "From: petrov@corp.local",
            "To: ivanov@corp.local",
            "Subject: Multipart",
            "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"corvex-boundary\"",
            "X-Corvex: root",
            "",
            "--corvex-boundary",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Transfer-Encoding: 7bit",
            "X-Part: first",
            "",
            "First body",
            "--corvex-boundary",
            "Content-Type: text/html; charset=UTF-8",
            "Content-Disposition: attachment; filename=\"page.html\"",
            "X-Part: second",
            "",
            "<b>Second body</b>",
            "--corvex-boundary--",
            "");

    private static final String SINGLE_TEXT = String.join("\r\n",
            "From: petrov@corp.local",
            "Subject: Single",
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=UTF-8",
            "X-Corvex: single",
            "",
            "Only body",
            "");

    private static final String EMBEDDED_TEXT = String.join("\r\n",
            "From: outer@corp.local",
            "Subject: Forwarded",
            "MIME-Version: 1.0",
            "Content-Type: message/rfc822",
            "",
            "From: inner@corp.local",
            "Subject: Inner message",
            "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"inner-boundary\"",
            "",
            "--inner-boundary",
            "Content-Type: text/plain; charset=UTF-8",
            "X-Inner-Part: yes",
            "",
            "Inner body",
            "--inner-boundary--",
            "");

    private static ImapStorageClient.MessageMeta meta(long uid, long modseq, long size) {
        return new ImapStorageClient.MessageMeta(MESSAGE_UUID, uid, modseq, "<m@corp.local>",
                "petrov@corp.local", "Тема", INTERNAL_DATE, size,
                true, false, true, false, false);
    }

    private CorvexMessageResult result(String raw, CorvexMailboxManager manager, MailboxSession session,
                                       ImapStorageClient.MessageMeta meta) throws MailboxException {
        when(manager.readRaw(session, FOLDER_UUID, meta)).thenReturn(raw.getBytes(StandardCharsets.UTF_8));
        return new CorvexMessageResult(manager, session, CorvexMailboxId.of(FOLDER_UUID), FOLDER_UUID, meta);
    }

    @Test
    void exposesMetadataComposedIdentityAndDelegatesMessageMetaData() throws Exception {
        CorvexMailboxManager manager = mock(CorvexMailboxManager.class);
        MailboxSession session = mock(MailboxSession.class);
        var meta = meta(7, 0, 1234);
        MessageMetaData metadata = new MessageMetaData(
                MessageUid.of(7), ModSeq.of(1), new Flags(Flags.Flag.SEEN), 1234,
                Date.from(INTERNAL_DATE), Optional.empty(), CorvexMessageId.of(MESSAGE_UUID),
                ThreadId.fromBaseMessageId(CorvexMessageId.of(MESSAGE_UUID)));
        when(manager.toMessageMetaData(meta)).thenReturn(metadata);
        CorvexMessageResult result = result(SINGLE_TEXT, manager, session, meta);

        assertEquals(CorvexMessageId.of(MESSAGE_UUID), result.getMessageId());
        assertEquals(ThreadId.fromBaseMessageId(CorvexMessageId.of(MESSAGE_UUID)), result.getThreadId());
        assertTrue(result.getSaveDate().isEmpty());
        assertEquals(Date.from(INTERNAL_DATE), result.getInternalDate());
        assertTrue(result.getFlags().contains(Flags.Flag.SEEN));
        assertTrue(result.getFlags().contains(Flags.Flag.FLAGGED));
        assertEquals(1234, result.getSize());
        assertEquals(MessageUid.of(7), result.getUid());
        assertEquals(ModSeq.of(1), result.getModSeq(), "modseq 0 нормализуется к минимальному James 1");
        assertEquals(CorvexMailboxId.of(FOLDER_UUID), result.getMailboxId());
        assertSame(metadata, result.messageMetaData());
        assertTrue(result.getLoadedAttachments().isEmpty());

        ComposedMessageId composed = result.composedId();
        assertEquals(CorvexMailboxId.of(FOLDER_UUID), composed.getMailboxId());
        assertEquals(CorvexMessageId.of(MESSAGE_UUID), composed.getMessageId());
        assertEquals(MessageUid.of(7), composed.getUid());
    }

    @Test
    void multipartRootAndPartsExposeDescriptorHeadersFullBodyAndCaching() throws Exception {
        CorvexMailboxManager manager = mock(CorvexMailboxManager.class);
        MailboxSession session = mock(MailboxSession.class);
        var meta = meta(7, 9, MULTIPART_TEXT.getBytes(StandardCharsets.UTF_8).length);
        CorvexMessageResult result = result(MULTIPART_TEXT, manager, session, meta);

        MimeDescriptor descriptor = result.getMimeDescriptor();
        assertEquals("multipart", descriptor.getMimeType());
        assertEquals("mixed", descriptor.getMimeSubType());
        assertEquals(0, descriptor.getBodyOctets(),
                "James composite descriptor не агрегирует body octets вложенных частей");
        List<MimeDescriptor> parts = toList(descriptor.parts());
        assertEquals(2, parts.size());
        assertEquals("text", parts.get(0).getMimeType());
        assertTrue(parts.get(0).getBodyOctets() > 0);
        assertTrue(parts.get(1).getBodyOctets() > 0);

        List<Header> rootHeaders = toList(result.iterateHeaders(null));
        assertTrue(rootHeaders.stream().anyMatch(h -> h.getName().equals("X-Corvex")
                && h.getValue().equals("root")));
        List<Header> rootMimeHeaders = toList(result.iterateMimeHeaders(new MimePath(new int[0])));
        assertTrue(rootMimeHeaders.stream().allMatch(h -> h.getName().startsWith("Content-")
                || h.getName().startsWith("MIME-")));
        assertFalse(rootMimeHeaders.stream().anyMatch(h -> h.getName().equals("X-Corvex")));

        MimePath first = new MimePath(new int[]{1});
        MimePath second = new MimePath(new int[]{2});
        assertTrue(toList(result.iterateHeaders(first)).stream()
                .anyMatch(h -> h.getName().equals("X-Part") && h.getValue().equals("first")));
        List<Header> firstMime = toList(result.iterateMimeHeaders(first));
        assertTrue(firstMime.stream().anyMatch(h -> h.getName().equals("Content-Transfer-Encoding")));
        assertFalse(firstMime.stream().anyMatch(h -> h.getName().equals("X-Part")));

        assertEquals(MULTIPART_TEXT, text(result.getFullContent()));
        assertEquals(MULTIPART_TEXT, text(result.getFullContent(null)));
        assertTrue(text(result.getBody()).contains("First body"));
        assertEquals("First body", text(result.getBody(first)).trim());
        assertEquals("<b>Second body</b>", text(result.getMimeBody(second)).trim());
        String fullSecond = text(result.getFullContent(second));
        assertTrue(fullSecond.contains("Content-Disposition: attachment"));
        assertTrue(fullSecond.contains("\r\n\r\n<b>Second body</b>"));

        var headers = result.getHeaders();
        assertTrue(text(headers.getInputStream().readAllBytes()).contains("Subject: Multipart"));
        assertEquals(headers.getInputStream().readAllBytes().length, headers.size());
        assertEquals(rootHeaders.size(), toList(headers.headers()).size());

        verify(manager, times(1)).readRaw(session, FOLDER_UUID, meta);
    }

    @Test
    void singlePartBodyOneIsWholeMessageAndBadPathsFail() throws Exception {
        CorvexMailboxManager manager = mock(CorvexMailboxManager.class);
        MailboxSession session = mock(MailboxSession.class);
        var meta = meta(8, 1, SINGLE_TEXT.getBytes(StandardCharsets.UTF_8).length);
        CorvexMessageResult result = result(SINGLE_TEXT, manager, session, meta);
        MimePath bodyOne = new MimePath(new int[]{1});

        assertEquals("text", result.getMimeDescriptor().getMimeType());
        assertEquals("Only body", text(result.getBody(bodyOne)).trim());
        assertEquals(SINGLE_TEXT, text(result.getFullContent(bodyOne)),
                "единственная часть одночастного письма представлена самим MimeMessage");
        assertTrue(toList(result.iterateHeaders(bodyOne)).stream()
                .anyMatch(h -> h.getName().equals("X-Corvex")));
        assertTrue(toList(result.iterateMimeHeaders(bodyOne)).stream()
                .anyMatch(h -> h.getName().equals("Content-Type")));

        assertThrows(MailboxException.class, () -> result.getBody(new MimePath(new int[]{0})));
        assertThrows(MailboxException.class, () -> result.getBody(new MimePath(new int[]{2})));
        assertThrows(MailboxException.class, () -> result.getBody(new MimePath(new int[]{2, 1})));
        assertThrows(MailboxException.class,
                () -> result.iterateHeaders(new MimePath(new int[]{99})));
    }

    @Test
    void multipartRejectsOutOfRangeAndNonMultipartContinuation() throws Exception {
        CorvexMailboxManager manager = mock(CorvexMailboxManager.class);
        MailboxSession session = mock(MailboxSession.class);
        CorvexMessageResult result = result(MULTIPART_TEXT, manager, session, meta(9, 1, 10));

        assertThrows(MailboxException.class, () -> result.getBody(new MimePath(new int[]{3})));
        assertThrows(MailboxException.class, () -> result.getBody(new MimePath(new int[]{-1})));
        assertThrows(MailboxException.class, () -> result.getBody(new MimePath(new int[]{2, 1})),
                "текстовая часть не имеет вложенных BODY[2.1]");
    }

    @Test
    void embeddedMessageCanBeAddressedAndThenItsMultipartParts() throws Exception {
        CorvexMailboxManager manager = mock(CorvexMailboxManager.class);
        MailboxSession session = mock(MailboxSession.class);
        CorvexMessageResult result = result(EMBEDDED_TEXT, manager, session, meta(10, 1, 10));
        MimePath embedded = new MimePath(new int[]{1});
        MimePath embeddedPart = new MimePath(new int[]{1, 1});

        assertEquals("message", result.getMimeDescriptor().getMimeType());
        assertTrue(toList(result.iterateHeaders(embedded)).stream()
                .anyMatch(h -> h.getName().equals("Subject") && h.getValue().equals("Inner message")));
        assertEquals("Inner body", text(result.getBody(embeddedPart)).trim());
        assertTrue(text(result.getFullContent(embeddedPart)).contains("X-Inner-Part: yes"));
        assertThrows(MailboxException.class, () -> result.getBody(new MimePath(new int[]{2})));
    }

    @Test
    void rawLoadFailureIsExposedAndDescriptorFailureKeepsCause() throws Exception {
        CorvexMailboxManager manager = mock(CorvexMailboxManager.class);
        MailboxSession session = mock(MailboxSession.class);
        var meta = meta(11, 1, 10);
        MailboxException failure = new MailboxException("storage unavailable");
        when(manager.readRaw(session, FOLDER_UUID, meta)).thenThrow(failure);
        CorvexMessageResult result = new CorvexMessageResult(
                manager, session, CorvexMailboxId.of(FOLDER_UUID), FOLDER_UUID, meta);

        assertSame(failure, assertThrows(MailboxException.class, result::getFullContent));
        MailboxException descriptorFailure = assertThrows(MailboxException.class, result::getMimeDescriptor);
        assertSame(failure, descriptorFailure.getCause());
        assertThrows(MailboxException.class, () -> result.getBody(new MimePath(new int[]{1})));
    }

    @Test
    void equalityHashAndUidOrderingIgnoreManagerAndSessionInstances() throws Exception {
        CorvexMailboxManager firstManager = mock(CorvexMailboxManager.class);
        CorvexMailboxManager secondManager = mock(CorvexMailboxManager.class);
        MailboxSession session = mock(MailboxSession.class);
        var sameMeta = meta(7, 5, 10);
        CorvexMessageResult first = new CorvexMessageResult(
                firstManager, session, CorvexMailboxId.of(FOLDER_UUID), FOLDER_UUID, sameMeta);
        CorvexMessageResult same = new CorvexMessageResult(
                secondManager, session, CorvexMailboxId.of(FOLDER_UUID), FOLDER_UUID, sameMeta);
        CorvexMessageResult otherUid = new CorvexMessageResult(
                secondManager, session, CorvexMailboxId.of(FOLDER_UUID), FOLDER_UUID, meta(8, 5, 10));

        assertEquals(first, first);
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, otherUid);
        assertNotEquals(first, null);
        assertNotEquals(first, "not a result");
        assertTrue(first.compareTo(otherUid) < 0);
        assertTrue(otherUid.compareTo(first) > 0);

        MessageResult other = mock(MessageResult.class);
        when(other.getUid()).thenReturn(MessageUid.of(7));
        assertEquals(0, first.compareTo(other));
    }

    @Test
    void staticHeaderAndBodySplittingSupportsCrlfLfAndNoSeparator() {
        byte[] crlf = "X-Test: one\r\n\r\nBody".getBytes(StandardCharsets.ISO_8859_1);
        assertArrayEquals("X-Test: one\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1),
                CorvexMessageResult.headerBytes(crlf));
        assertArrayEquals("Body".getBytes(StandardCharsets.ISO_8859_1),
                CorvexMessageResult.bodyBytes(crlf));

        byte[] lf = "X-Test: one\n\nBody".getBytes(StandardCharsets.ISO_8859_1);
        assertArrayEquals("X-Test: one\n\n".getBytes(StandardCharsets.ISO_8859_1),
                CorvexMessageResult.headerBytes(lf));
        assertArrayEquals("Body".getBytes(StandardCharsets.ISO_8859_1),
                CorvexMessageResult.bodyBytes(lf));

        byte[] noSeparator = "X-Test: one".getBytes(StandardCharsets.ISO_8859_1);
        assertArrayEquals(noSeparator, CorvexMessageResult.headerBytes(noSeparator));
        assertArrayEquals(new byte[0], CorvexMessageResult.bodyBytes(noSeparator));

        byte[] lfEarlier = "X-Test: one\n\nBody\r\n\r\nTail".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("Body\r\n\r\nTail", text(CorvexMessageResult.bodyBytes(lfEarlier)));

        List<Header> headers = CorvexMessageResult.parseHeaders(crlf);
        assertEquals(1, headers.size());
        assertEquals("X-Test", headers.get(0).getName());
        assertEquals("one", headers.get(0).getValue());
    }

    private static <T> List<T> toList(Iterator<T> iterator) {
        return java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(iterator, java.util.Spliterator.ORDERED), false)
                .toList();
    }

    private static String text(Content content) throws IOException {
        return text(content.getInputStream().readAllBytes());
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
