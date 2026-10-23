package ru.corvex.mailstorage.service;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.ExternalMailAccount;
import ru.corvex.mailstorage.domain.ExternalMailImportedMessage;
import ru.corvex.mailstorage.domain.MailFolder;
import ru.corvex.mailstorage.domain.MessageMetadata;
import ru.corvex.mailstorage.mime.MimeMailParser;
import ru.corvex.mailstorage.pipeline.MailPipelineEvents;
import ru.corvex.mailstorage.repository.ExternalMailImportedMessageRepository;
import ru.corvex.mailstorage.repository.MailFolderRepository;
import ru.corvex.mailstorage.repository.MessageMetadataRepository;
import ru.corvex.mailstorage.storage.MaildirStorage;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailDeliveryServiceTest {

    @TempDir
    Path tmp;

    private final UUID ownerId = UUID.randomUUID();
    private final String mailbox = "user@company.local";

    private RawMessageSpool spool;
    private MaildirStorage maildir;
    private MailFolderRepository folders;
    private MessageMetadataRepository messages;
    private ExternalMailImportedMessageRepository externalImported;
    private AuthUserClient auth;
    private JdbcTemplate jdbc;
    private MailDeliveryService service;

    private MailFolder inbox;
    private MailFolder junk;
    private MailFolder sent;
    private final AtomicReference<MessageMetadata> savedMessage = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        spool = new RawMessageSpool(tmp.resolve("spool"), 10_000_000);
        maildir = new MaildirStorage(tmp.resolve("mail"), tmp.resolve("attachments"));
        folders = mock(MailFolderRepository.class);
        messages = mock(MessageMetadataRepository.class);
        externalImported = mock(ExternalMailImportedMessageRepository.class);
        auth = mock(AuthUserClient.class);
        jdbc = mock(JdbcTemplate.class);
        service = new MailDeliveryService(
                spool, new MimeMailParser(maildir), maildir, folders, messages, externalImported, auth, jdbc);

        inbox = folder("INBOX", "INBOX", 7, 3);
        junk = folder("Junk", "SPAM", 11, 5);
        sent = folder("Sent", "SENT", 13, 9);
        for (MailFolder folder : List.of(inbox, junk, sent)) {
            when(folders.findFirstByOwnerIdAndSpecialUseIgnoreCase(ownerId, folder.getSpecialUse()))
                    .thenReturn(Optional.of(folder));
            when(folders.lockById(folder.getId())).thenReturn(Optional.of(folder));
        }
        when(folders.save(any(MailFolder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messages.saveAndFlush(any(MessageMetadata.class))).thenAnswer(inv -> {
            MessageMetadata message = inv.getArgument(0);
            if (message.getId() == null) message.setId(UUID.randomUUID());
            savedMessage.set(message);
            return message;
        });
        when(externalImported.findFirstByRawRef(anyString())).thenReturn(Optional.empty());
        when(messages.sumSizeByOwnerId(ownerId)).thenReturn(0L);
    }

    @Test
    void skipsMissingOrInactiveRecipient() throws Exception {
        StoredEvent stored = storedEvent("Subject: x\r\n\r\nbody", false, "CLEAN", null, false);
        when(auth.findByEmail(mailbox)).thenReturn(Optional.empty());
        assertTrue(service.deliver(stored.event(), "USER@company.local").isEmpty());

        when(auth.findByEmail(mailbox)).thenReturn(Optional.of(owner("DISABLED", 0)));
        assertTrue(service.deliver(stored.event(), mailbox).isEmpty());
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void deliversCleanMessageToInboxAndStoresParsedMetadata() throws Exception {
        String eml = """
                From: Sender <sender@partner.test>\r
                To: user@company.local\r
                Cc: copy@company.local\r
                Subject: Договор\r
                Message-ID: <delivery@x>\r
                MIME-Version: 1.0\r
                Content-Type: multipart/mixed; boundary="b"\r
                \r
                --b\r
                Content-Type: text/plain; charset=utf-8\r
                \r
                Тело письма\r
                --b\r
                Content-Type: text/plain; name="a.txt"\r
                Content-Disposition: attachment; filename="a.txt"\r
                Content-Transfer-Encoding: base64\r
                \r
                aGVsbG8=\r
                --b--\r
                """;
        StoredEvent stored = storedEvent(eml, false, "clean", 1.25, true);
        stubOwner("ACTIVE", 0);

        var result = service.deliver(stored.event(), "User <USER@company.local>");

        assertTrue(result.isPresent());
        assertEquals(inbox.getId(), result.get().folderId());
        assertEquals(7, result.get().uid());
        assertEquals(8, inbox.getUidNext());
        assertEquals(4, inbox.getHighestModseq());
        MessageMetadata saved = savedMessage.get();
        assertEquals("<delivery@x>", saved.getMessageIdHeader());
        assertEquals("Sender <sender@partner.test>", saved.getSender());
        assertEquals("Договор", saved.getSubject());
        assertEquals(MessageMetadata.AvStatus.CLEAN, saved.getAvStatus());
        assertEquals(1.25, saved.getSpamScore());
        assertFalse(saved.isSeen());
        assertTrue(Files.exists(tmp.resolve("mail").resolve(saved.getMaildirPath())));
    }

    @Test
    void spamWithoutCheckedScoreGoesToJunkAndUnknownAvBecomesError() throws Exception {
        StoredEvent stored = storedEvent("Subject: spam\r\n\r\nbuy", true, "mystery", 99.0, false);
        stubOwner("ACTIVE", 0);

        var result = service.deliver(stored.event(), mailbox);

        assertTrue(result.isPresent());
        assertEquals(junk.getId(), result.get().folderId());
        assertEquals(MessageMetadata.AvStatus.ERROR, savedMessage.get().getAvStatus());
        assertNull(savedMessage.get().getSpamScore(), "непроверенный spam score нельзя выдавать за измеренный");
    }

    @Test
    void duplicateDeliveryMarksQueuedExternalRecordDelivered() throws Exception {
        StoredEvent stored = storedEvent("Subject: duplicate\r\n\r\nbody", false, "CLEAN", null, false);
        stubOwner("ACTIVE", 0);
        ExternalMailImportedMessage external = externalRecord(ownerId);
        external.setStatus(ExternalMailImportedMessage.Status.QUEUED);
        when(externalImported.findFirstByRawRef(stored.raw().rawRef())).thenReturn(Optional.of(external));
        when(messages.existsByRawRefAndOwnerId(stored.raw().rawRef(), ownerId)).thenReturn(true);

        assertTrue(service.deliver(stored.event(), mailbox).isEmpty());

        assertEquals(ExternalMailImportedMessage.Status.DELIVERED, external.getStatus());
        verify(externalImported).save(external);
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void quotaOverflowSkipsDeliveryAndFailsExternalRecord() throws Exception {
        StoredEvent stored = storedEvent("Subject: large\r\n\r\nbody", false, "CLEAN", null, false);
        stubOwner("ACTIVE", stored.raw().sizeBytes());
        when(messages.sumSizeByOwnerId(ownerId)).thenReturn(1L);
        ExternalMailImportedMessage external = externalRecord(ownerId);
        when(externalImported.findFirstByRawRef(stored.raw().rawRef())).thenReturn(Optional.of(external));

        assertTrue(service.deliver(stored.event(), mailbox).isEmpty());

        assertEquals(ExternalMailImportedMessage.Status.FAILED, external.getStatus());
        assertTrue(external.getError().contains("Квота"));
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void infectedExternalMessageOverridesTargetButPreservesFlagsAndDate() throws Exception {
        StoredEvent stored = storedEvent("From: a@x\r\nSubject: virus\r\n\r\nbody", false, "INFECTED", null, false);
        stubOwner("ACTIVE", 0);
        MailFolder target = folder("Imported", null, 31, 2);
        ExternalMailImportedMessage external = externalRecord(ownerId);
        external.setTargetFolder(target);
        external.setPreserveTargetFolder(true);
        external.setSeen(true);
        external.setAnswered(true);
        external.setFlagged(true);
        external.setDraft(true);
        external.setSourceInternalDate(Instant.parse("2020-02-03T04:05:06Z"));
        external.setError("old error");
        when(externalImported.findFirstByRawRef(stored.raw().rawRef())).thenReturn(Optional.of(external));

        var result = service.deliver(stored.event(), mailbox);

        assertTrue(result.isPresent());
        assertEquals(junk.getId(), result.get().folderId());
        MessageMetadata saved = savedMessage.get();
        assertTrue(saved.isSeen() && saved.isAnswered() && saved.isFlagged() && saved.isDraft());
        assertEquals(Instant.parse("2020-02-03T04:05:06Z"), saved.getInternalDate());
        assertEquals(ExternalMailImportedMessage.Status.DELIVERED, external.getStatus());
        assertEquals(saved.getId(), external.getCorvexMessageId());
        assertNull(external.getError());
    }

    @Test
    void externalSpamUsesJunkUnlessTargetMustBePreserved() throws Exception {
        StoredEvent first = storedEvent("Subject: one\r\n\r\nbody", true, "CLEAN", null, false);
        stubOwner("ACTIVE", 0);
        MailFolder target = folder("Imported", null, 21, 2);
        when(folders.findByIdAndOwnerId(target.getId(), ownerId)).thenReturn(Optional.of(target));
        when(folders.lockById(target.getId())).thenReturn(Optional.of(target));
        ExternalMailImportedMessage notPreserved = externalRecord(ownerId);
        notPreserved.setTargetFolder(target);
        notPreserved.setPreserveTargetFolder(false);
        when(externalImported.findFirstByRawRef(first.raw().rawRef())).thenReturn(Optional.of(notPreserved));

        assertEquals(junk.getId(), service.deliver(first.event(), mailbox).orElseThrow().folderId());

        StoredEvent second = storedEvent("Subject: two\r\n\r\nbody", true, "CLEAN", null, false);
        ExternalMailImportedMessage preserved = externalRecord(ownerId);
        preserved.setTargetFolder(target);
        preserved.setPreserveTargetFolder(true);
        when(externalImported.findFirstByRawRef(second.raw().rawRef())).thenReturn(Optional.of(preserved));

        var delivered = service.deliver(second.event(), mailbox).orElseThrow();
        assertEquals(target.getId(), delivered.folderId());
        assertEquals(21, delivered.uid());
    }

    @Test
    void externalRecordForAnotherOwnerIsIgnored() throws Exception {
        StoredEvent stored = storedEvent("From: a@x\r\nSubject: x\r\n\r\nbody", false, "CLEAN", null, false);
        stubOwner("ACTIVE", 0);
        ExternalMailImportedMessage foreign = externalRecord(UUID.randomUUID());
        foreign.setSeen(true);
        when(externalImported.findFirstByRawRef(stored.raw().rawRef())).thenReturn(Optional.of(foreign));

        assertTrue(service.deliver(stored.event(), mailbox).isPresent());

        assertFalse(savedMessage.get().isSeen());
        verify(externalImported, never()).save(any());
    }

    @Test
    void mockedParserCoversFallbacksTruncationAndJdbcInserts() throws Exception {
        RawMessageSpool.StoredRawMessage raw = spool.store(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        MimeMailParser parser = mock(MimeMailParser.class);
        MimeMailParser.ParsedMail parsed = new MimeMailParser.ParsedMail(
                "m".repeat(1100), " ", "s".repeat(1100), "p".repeat(600), "body", null, null,
                List.of(new MimeMailParser.ParsedAddress("FIRST@company.local", "First"),
                        new MimeMailParser.ParsedAddress(" ", null)),
                List.of(new MimeMailParser.ParsedAddress("copy@company.local", null)),
                List.of(new MimeMailParser.ParsedAttachment("skip", "text/plain", 1, null),
                        new MimeMailParser.ParsedAttachment("kept", "application/pdf", 2, "a".repeat(64))));
        when(parser.parse(any(InputStream.class), eq(true))).thenReturn(parsed);
        MailDeliveryService local = new MailDeliveryService(
                spool, parser, maildir, folders, messages, externalImported, auth, jdbc);
        stubOwner("ACTIVE", 0);
        Instant receivedAt = Instant.parse("2026-03-04T05:06:07Z");
        MailPipelineEvents.AvCheckedEvent event = new MailPipelineEvents.AvCheckedEvent(
                raw.rawRef(), "Envelope <ENVELOPE@partner.test>", List.of(mailbox), "smtp",
                raw.sizeBytes(), raw.sha256(), receivedAt, false, null, 5.0, false, null, null);

        assertTrue(local.deliver(event, mailbox).isPresent());

        MessageMetadata saved = savedMessage.get();
        assertEquals(998, saved.getMessageIdHeader().length());
        assertEquals("envelope@partner.test", saved.getSender());
        assertEquals(998, saved.getSubject().length());
        assertEquals(512, saved.getSnippet().length());
        assertEquals(receivedAt, saved.getInternalDate());
        assertEquals(MessageMetadata.AvStatus.ERROR, saved.getAvStatus());
    }

    @Test
    void parserFailureBecomesIllegalState() throws Exception {
        RawMessageSpool.StoredRawMessage raw = spool.store(new ByteArrayInputStream(new byte[]{1}));
        MimeMailParser parser = mock(MimeMailParser.class);
        when(parser.parse(any(InputStream.class), eq(true))).thenThrow(new MessagingException("broken"));
        MailDeliveryService local = new MailDeliveryService(
                spool, parser, maildir, folders, messages, externalImported, auth, jdbc);
        stubOwner("ACTIVE", 0);
        MailPipelineEvents.AvCheckedEvent event = event(raw, false, "CLEAN", null, false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> local.deliver(event, mailbox));
        assertTrue(error.getMessage().contains(raw.rawRef()));
    }

    @Test
    void sentCopyHonorsInactiveDuplicateAndQuotaGuards() throws Exception {
        RawMessageSpool.StoredRawMessage raw = store("Subject: out\r\n\r\nbody");

        assertTrue(service.storeSentCopy(owner("DISABLED", 0), raw.rawRef(), raw.sizeBytes()).isEmpty());

        when(messages.existsByRawRefAndOwnerId(raw.rawRef(), ownerId)).thenReturn(true);
        assertTrue(service.storeSentCopy(owner("ACTIVE", 0), raw.rawRef(), raw.sizeBytes()).isEmpty());

        when(messages.existsByRawRefAndOwnerId(raw.rawRef(), ownerId)).thenReturn(false);
        when(messages.sumSizeByOwnerId(ownerId)).thenReturn(raw.sizeBytes());
        assertTrue(service.storeSentCopy(owner("ACTIVE", raw.sizeBytes()), raw.rawRef(), raw.sizeBytes()).isEmpty());
    }

    @Test
    void storesSentCopyAsSeenCleanMessage() throws Exception {
        RawMessageSpool.StoredRawMessage raw = store("""
                From: user@company.local\r
                To: dest@partner.test\r
                Subject: Outgoing\r
                Message-ID: <out@x>\r
                Content-Type: text/plain; charset=utf-8\r
                \r
                hello\r
                """);

        var result = service.storeSentCopy(owner("ACTIVE", 0), raw.rawRef(), raw.sizeBytes());

        assertTrue(result.isPresent());
        assertEquals(sent.getId(), result.get().folderId());
        assertEquals(13, result.get().uid());
        MessageMetadata saved = savedMessage.get();
        assertTrue(saved.isSeen());
        assertEquals(MessageMetadata.AvStatus.CLEAN, saved.getAvStatus());
        assertEquals(raw.rawRef(), saved.getRawRef());
        assertTrue(Files.exists(tmp.resolve("mail").resolve(saved.getMaildirPath())));
    }

    @Test
    void ensureFolderReturnsExistingOrCreatesWithProtocolDefaults() {
        assertEquals(inbox, service.ensureFolder(ownerId, "INBOX", "INBOX"));

        when(folders.findFirstByOwnerIdAndSpecialUseIgnoreCase(ownerId, "ARCHIVE"))
                .thenReturn(Optional.empty());
        when(folders.saveAndFlush(any(MailFolder.class))).thenAnswer(inv -> {
            MailFolder folder = inv.getArgument(0);
            folder.setId(UUID.randomUUID());
            return folder;
        });

        MailFolder created = service.ensureFolder(ownerId, "ARCHIVE", "Archive");

        assertEquals(ownerId, created.getOwnerId());
        assertEquals("Archive", created.getName());
        assertEquals("ARCHIVE", created.getSpecialUse());
        assertEquals(1, created.getUidNext());
        assertEquals(1, created.getHighestModseq());
        assertTrue(created.getUidValidity() >= 1);
    }

    @Test
    void addressNormalizationCoversDisplayAndMalformedForms() {
        assertEquals("", MailDeliveryService.normalizeAddress(null));
        assertEquals("user@company.local", MailDeliveryService.normalizeAddress(" User <USER@company.local> "));
        assertEquals("user@company.local", MailDeliveryService.normalizeAddress("<USER@company.local"));
        assertEquals("plain", MailDeliveryService.normalizeAddress(" PLAIN "));
    }

    private record StoredEvent(RawMessageSpool.StoredRawMessage raw, MailPipelineEvents.AvCheckedEvent event) {
    }

    private StoredEvent storedEvent(String eml, boolean spam, String avStatus, Double score, boolean checked)
            throws Exception {
        RawMessageSpool.StoredRawMessage raw = store(eml);
        return new StoredEvent(raw, new MailPipelineEvents.AvCheckedEvent(
                raw.rawRef(), "sender@partner.test", List.of(mailbox), "198.51.100.1",
                raw.sizeBytes(), raw.sha256(), Instant.parse("2026-01-01T00:00:00Z"),
                spam, score, 5.0, checked, avStatus, null));
    }

    private MailPipelineEvents.AvCheckedEvent event(RawMessageSpool.StoredRawMessage raw, boolean spam,
                                                    String avStatus, Double score, boolean checked) {
        return new MailPipelineEvents.AvCheckedEvent(
                raw.rawRef(), "sender@partner.test", List.of(mailbox), "smtp", raw.sizeBytes(), raw.sha256(),
                Instant.now(), spam, score, 5.0, checked, avStatus, null);
    }

    private RawMessageSpool.StoredRawMessage store(String eml) throws Exception {
        return spool.store(new ByteArrayInputStream(eml.getBytes(StandardCharsets.UTF_8)));
    }

    private void stubOwner(String status, long quota) {
        when(auth.findByEmail(mailbox)).thenReturn(Optional.of(owner(status, quota)));
    }

    private AuthUserClient.MailboxOwner owner(String status, long quota) {
        return new AuthUserClient.MailboxOwner(ownerId, mailbox, "Test User", status, quota);
    }

    private ExternalMailImportedMessage externalRecord(UUID recordOwnerId) {
        ExternalMailAccount account = new ExternalMailAccount();
        account.setId(UUID.randomUUID());
        account.setOwnerId(recordOwnerId);
        ExternalMailImportedMessage record = new ExternalMailImportedMessage();
        record.setId(UUID.randomUUID());
        record.setAccount(account);
        record.setRemoteFolder("INBOX");
        record.setRemoteUid("42");
        record.setStatus(ExternalMailImportedMessage.Status.QUEUED);
        return record;
    }

    private MailFolder folder(String name, String specialUse, long uidNext, long highestModseq) {
        MailFolder folder = new MailFolder();
        folder.setId(UUID.randomUUID());
        folder.setOwnerId(ownerId);
        folder.setName(name);
        folder.setSpecialUse(specialUse);
        folder.setUidNext(uidNext);
        folder.setUidValidity(123);
        folder.setHighestModseq(highestModseq);
        return folder;
    }
}
