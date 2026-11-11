package ru.corvex.mailstorage.service;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.MailDtos;
import ru.corvex.mailstorage.av.ClamAvClient;
import ru.corvex.mailstorage.domain.MailFolder;
import ru.corvex.mailstorage.domain.MessageMetadata;
import ru.corvex.mailstorage.mime.MimeMailParser;
import ru.corvex.mailstorage.repository.MailFolderRepository;
import ru.corvex.mailstorage.repository.MessageMetadataRepository;
import ru.corvex.mailstorage.storage.MaildirStorage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailApiServiceTest {

    @TempDir
    Path tmp;

    private final UUID ownerId = UUID.randomUUID();

    private MailFolderRepository folders;
    private MessageMetadataRepository messages;
    private MailDeliveryService delivery;
    private MaildirStorage maildir;
    private JdbcTemplate jdbc;
    private ClamAvClient clam;
    private MailApiService service;

    @BeforeEach
    void setUp() {
        folders = mock(MailFolderRepository.class);
        messages = mock(MessageMetadataRepository.class);
        delivery = mock(MailDeliveryService.class);
        maildir = new MaildirStorage(tmp.resolve("mail"), tmp.resolve("attachments"));
        jdbc = mock(JdbcTemplate.class);
        clam = mock(ClamAvClient.class);
        service = new MailApiService(folders, messages, delivery, maildir, new MimeMailParser(maildir), jdbc, clam);
        when(folders.save(any(MailFolder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messages.save(any(MessageMetadata.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void listFoldersEnsuresDefaultsAndCountsMessages() {
        MailFolder inbox = folder("INBOX", "INBOX");
        MailFolder sent = folder("Sent", "SENT");
        when(folders.findAllByOwnerIdOrderBySpecialUseAscNameAsc(ownerId)).thenReturn(List.of(inbox, sent));
        when(messages.countByFolder_Id(inbox.getId())).thenReturn(10L);
        when(messages.countByFolder_IdAndSeenFalse(inbox.getId())).thenReturn(3L);
        when(messages.countByFolder_Id(sent.getId())).thenReturn(2L);
        when(messages.countByFolder_IdAndSeenFalse(sent.getId())).thenReturn(0L);

        List<MailDtos.FolderView> result = service.listFolders(ownerId);

        assertEquals(2, result.size());
        assertEquals(10, result.get(0).total());
        assertEquals(3, result.get(0).unread());
        verify(delivery, times(6)).ensureFolder(eq(ownerId), anyString(), anyString());
    }

    @Test
    void listMessagesUsesCursorClampsLimitAndMarksAttachments() {
        MailFolder inbox = folder("INBOX", "INBOX");
        MessageMetadata first = message(inbox, 9);
        MessageMetadata second = message(inbox, 8);
        when(folders.findByIdAndOwnerId(inbox.getId(), ownerId)).thenReturn(Optional.of(inbox));
        when(messages.listFolderBeforeUid(eq(inbox.getId()), eq(ownerId), eq(50L), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class)))
                .thenReturn(List.of(second.getId()));

        List<MailDtos.MessageListItem> result = service.listMessages(ownerId, inbox.getId(), 50L, 500);

        assertEquals(2, result.size());
        assertFalse(result.get(0).hasAttachments());
        assertTrue(result.get(1).hasAttachments());
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(messages).listFolderBeforeUid(eq(inbox.getId()), eq(ownerId), eq(50L), page.capture());
        assertEquals(100, page.getValue().getPageSize());
    }

    @Test
    void listMessagesDefaultsCursorAndMinimumLimitWithoutAttachmentQueryForEmptyPage() {
        MailFolder inbox = folder("INBOX", "INBOX");
        when(folders.findByIdAndOwnerId(inbox.getId(), ownerId)).thenReturn(Optional.of(inbox));
        when(messages.listFolderBeforeUid(eq(inbox.getId()), eq(ownerId), eq(Long.MAX_VALUE), any(Pageable.class)))
                .thenReturn(List.of());

        assertTrue(service.listMessages(ownerId, inbox.getId(), null, 0).isEmpty());

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(messages).listFolderBeforeUid(eq(inbox.getId()), eq(ownerId), eq(Long.MAX_VALUE), page.capture());
        assertEquals(1, page.getValue().getPageSize());
        verify(jdbc, never()).queryForList(anyString(), eq(UUID.class), any(Object[].class));
    }

    @Test
    void listMessagesThrowsWhenFolderIsNotOwned() {
        UUID folderId = UUID.randomUUID();
        when(folders.findByIdAndOwnerId(folderId, ownerId)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.listMessages(ownerId, folderId, null, 10));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void getMessageParsesBodyAndMapsAttachmentRows() throws Exception {
        MailFolder inbox = folder("INBOX", "INBOX");
        String rel = maildir.storeMessage("user@company.local", "INBOX", bytes("""
                From: sender@partner.test\r
                To: user@company.local\r
                Cc: copy@company.local\r
                Subject: Detail\r
                Content-Type: text/plain; charset=utf-8\r
                \r
                Detail body\r
                """)).toString();
        MessageMetadata message = message(inbox, 4);
        message.setMaildirPath(rel);
        when(messages.findByIdAndOwnerId(message.getId(), ownerId)).thenReturn(Optional.of(message));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<MailDtos.AttachmentView>>any(), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<MailDtos.AttachmentView> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString(1)).thenReturn("a".repeat(64));
                    when(rs.getString(2)).thenReturn("file.txt");
                    when(rs.getLong(3)).thenReturn(12L);
                    when(rs.getString(4)).thenReturn("text/plain");
                    when(rs.getString(5)).thenReturn("CLEAN");
                    return List.of(mapper.mapRow(rs, 0));
                });

        MailDtos.MessageDetail detail = service.getMessage(ownerId, message.getId());

        assertEquals(List.of("user@company.local"), detail.to());
        assertEquals(List.of("copy@company.local"), detail.cc());
        assertTrue(detail.bodyText().contains("Detail body"));
        assertEquals(1, detail.attachments().size());
        assertEquals("file.txt", detail.attachments().get(0).fileName());
        assertEquals("UNVERIFIED", detail.attachments().get(0).avStatus());
        assertTrue(detail.summary().hasAttachments());
    }

    @Test
    void downloadRequiresOwnedMessageAndConfirmedCleanStatus() throws Exception {
        MailFolder inbox = folder("INBOX", "INBOX");
        MessageMetadata message = message(inbox, 1);
        var stored = maildir.storeAttachment(bytes("attachment content"));
        when(messages.findByIdAndOwnerId(message.getId(), ownerId)).thenReturn(Optional.of(message));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object[]>>any(), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<Object[]> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString(1)).thenReturn(stored.sha256());
                    when(rs.getString(2)).thenReturn("тест.txt");
                    when(rs.getLong(3)).thenReturn(stored.sizeBytes());
                    return List.<Object[]>of(mapper.mapRow(rs, 0));
                });

        // Historical CLEAN is not proof: it requires an on-demand scan.
        message.setAvStatus(MessageMetadata.AvStatus.CLEAN);
        when(clam.scan(any(InputStream.class))).thenReturn(new ClamAvClient.ScanResult(ClamAvClient.Result.CLEAN, null));
        try (var stream = service.downloadAttachment(ownerId, message.getId(), 0).stream()) {
            assertEquals("attachment content", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        verify(clam).scan(any(InputStream.class));
        when(clam.scan(any(InputStream.class))).thenReturn(new ClamAvClient.ScanResult(ClamAvClient.Result.ERROR, null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> service.downloadAttachment(ownerId, message.getId(), 0)).getStatusCode());
        when(clam.scan(any(InputStream.class))).thenReturn(new ClamAvClient.ScanResult(ClamAvClient.Result.INFECTED, "test"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.downloadAttachment(ownerId, message.getId(), 0)).getStatusCode());
        message.setAvScanVerified(true);
        message.setAvStatus(MessageMetadata.AvStatus.INFECTED);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.downloadAttachment(ownerId, message.getId(), 0)).getStatusCode());
        message.setAvStatus(MessageMetadata.AvStatus.CLEAN);
        try (var stream = service.downloadAttachment(ownerId, message.getId(), 0).stream()) {
            assertEquals("attachment content", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        UUID stranger = UUID.randomUUID();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.downloadAttachment(stranger, message.getId(), 0)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.downloadAttachment(ownerId, message.getId(), -1)).getStatusCode());
    }

    @Test
    void inlineImageRequiresOwnerAndCleanAntivirusResult() throws Exception {
        String raw = "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=b\r\n\r\n"
                + "--b\r\nContent-Type: image/png\r\nContent-ID: <logo>\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/aS0AAAAASUVORK5CYII=\r\n--b--\r\n";
        MessageMetadata message = message(folder("INBOX", "INBOX"), 1);
        message.setMaildirPath(maildir.storeMessage("user@company.local", "INBOX", bytes(raw)).toString());
        when(messages.findByIdAndOwnerId(message.getId(), ownerId)).thenReturn(Optional.of(message));
        UUID stranger = UUID.randomUUID();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.inlineImage(stranger, message.getId(), "logo")).getStatusCode());
        verify(clam, never()).scan(any());
        when(clam.scan(any(InputStream.class))).thenReturn(new ClamAvClient.ScanResult(ClamAvClient.Result.CLEAN, null));
        assertEquals("image/png", service.inlineImage(ownerId, message.getId(), "logo").contentType());
        when(clam.scan(any(InputStream.class))).thenReturn(new ClamAvClient.ScanResult(ClamAvClient.Result.INFECTED, "test"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.inlineImage(ownerId, message.getId(), "logo")).getStatusCode());
        when(clam.scan(any(InputStream.class))).thenReturn(new ClamAvClient.ScanResult(ClamAvClient.Result.ERROR, null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> service.inlineImage(ownerId, message.getId(), "logo")).getStatusCode());
    }

    @Test
    void downloadRejectsMissingAttachment() {
        MessageMetadata message = message(folder("INBOX", "INBOX"), 1);
        when(messages.findByIdAndOwnerId(message.getId(), ownerId)).thenReturn(Optional.of(message));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object[]>>any(), any(Object[].class)))
                .thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.downloadAttachment(ownerId, message.getId(), 99)).getStatusCode());
    }

    @Test
    void getMessageWrapsMimeFailureAsIOException() throws Exception {
        MailFolder inbox = folder("INBOX", "INBOX");
        String rel = maildir.storeMessage("user@company.local", "INBOX", bytes("raw")).toString();
        MessageMetadata message = message(inbox, 1);
        message.setMaildirPath(rel);
        when(messages.findByIdAndOwnerId(message.getId(), ownerId)).thenReturn(Optional.of(message));
        MimeMailParser parser = mock(MimeMailParser.class);
        when(parser.parse(any(InputStream.class), eq(false))).thenThrow(new MessagingException("broken mime"));
        MailApiService local = new MailApiService(folders, messages, delivery, maildir, parser, jdbc, clam);

        IOException error = assertThrows(IOException.class, () -> local.getMessage(ownerId, message.getId()));
        assertTrue(error.getCause() instanceof MessagingException);
    }

    @Test
    void updateFlagsWithoutChangesDoesNotAllocateModseq() {
        MailFolder inbox = folder("INBOX", "INBOX");
        MessageMetadata message = message(inbox, 3);
        message.setSeen(true);
        message.setFlagged(true);
        when(messages.findByIdAndOwnerId(message.getId(), ownerId)).thenReturn(Optional.of(message));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        var result = service.updateFlags(ownerId, message.getId(),
                new MailDtos.UpdateFlagsRequest(true, null, null, null));

        assertTrue(result.seen());
        assertTrue(result.flagged());
        assertFalse(result.hasAttachments());
        verify(folders, never()).lockById(any());
    }

    @Test
    void updateFlagsChangesAllProvidedFieldsAndAllocatesModseq() {
        MailFolder inbox = folder("INBOX", "INBOX");
        inbox.setHighestModseq(0);
        MessageMetadata message = message(inbox, 3);
        when(messages.findByIdAndOwnerId(message.getId(), ownerId)).thenReturn(Optional.of(message));
        when(folders.lockById(inbox.getId())).thenReturn(Optional.of(inbox));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(2);

        var result = service.updateFlags(ownerId, message.getId(),
                new MailDtos.UpdateFlagsRequest(true, true, true, true));

        assertTrue(result.seen() && result.flagged() && result.answered());
        assertTrue(message.isDeleted());
        assertEquals(2, inbox.getHighestModseq());
        assertEquals(2, message.getModseq());
        assertTrue(result.hasAttachments());
    }

    @Test
    void moveWithinSameFolderReturnsCurrentMessage() {
        MailFolder inbox = folder("INBOX", "INBOX");
        MessageMetadata message = message(inbox, 8);
        when(messages.lockOwned(message.getId(), ownerId)).thenReturn(Optional.of(message));
        when(folders.findByIdAndOwnerId(inbox.getId(), ownerId)).thenReturn(Optional.of(inbox));
        when(folders.lockById(inbox.getId())).thenReturn(Optional.of(inbox));

        var result = assertNoIo(() -> service.moveMessage(ownerId, message.getId(), inbox.getId()));

        assertEquals(8, result.uid());
        verify(messages, never()).save(any());
    }

    @Test
    void moveKeepsOriginalUntilCommitAndCleansItAfterCommit() throws Exception {
        checkMoveCompletion(TransactionSynchronization.STATUS_COMMITTED);
    }

    @Test
    void moveRollbackKeepsOriginalAndDeletesStagedCopy() throws Exception {
        checkMoveCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    }

    @Test
    void moveUnknownOutcomeKeepsBothCopiesForRecovery() throws Exception {
        checkMoveCompletion(TransactionSynchronization.STATUS_UNKNOWN);
    }

    private void checkMoveCompletion(int status) throws Exception {
        MailFolder inbox = folder("INBOX", "INBOX");
        MailFolder archive = folder("Archive", "ARCHIVE");
        archive.setUidNext(41);
        archive.setHighestModseq(0);
        String rel = maildir.storeMessage("user@company.local", "INBOX", bytes("to archive"), true).toString();
        MessageMetadata message = message(inbox, 8);
        message.setSeen(true);
        message.setMaildirPath(rel);
        when(messages.lockOwned(message.getId(), ownerId)).thenReturn(Optional.of(message));
        when(folders.findByIdAndOwnerId(archive.getId(), ownerId)).thenReturn(Optional.of(archive));
        when(folders.lockById(archive.getId())).thenReturn(Optional.of(archive));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(null);
        TransactionSynchronizationManager.initSynchronization();
        try {
            var result = service.moveMessage(ownerId, message.getId(), archive.getId());
            assertEquals(archive.getId(), result.folderId());
            assertEquals(41, result.uid());
            assertEquals(42, archive.getUidNext());
            assertEquals(2, message.getModseq());
            assertTrue(Files.exists(tmp.resolve("mail").resolve(rel)));
            assertTrue(Files.exists(tmp.resolve("mail").resolve(message.getMaildirPath())));
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(status));
            assertEquals(status != TransactionSynchronization.STATUS_COMMITTED, Files.exists(tmp.resolve("mail").resolve(rel)));
            assertEquals(status != TransactionSynchronization.STATUS_ROLLED_BACK, Files.exists(tmp.resolve("mail").resolve(message.getMaildirPath())));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void moveRejectsMissingTargetOrMessage() {
        MailFolder inbox = folder("INBOX", "INBOX");
        MessageMetadata message = message(inbox, 1);
        UUID missingTarget = UUID.randomUUID();
        when(messages.lockOwned(message.getId(), ownerId)).thenReturn(Optional.of(message));
        when(folders.findByIdAndOwnerId(missingTarget, ownerId)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.moveMessage(ownerId, message.getId(), missingTarget)).getStatusCode());

        UUID missingMessage = UUID.randomUUID();
        when(messages.findByIdAndOwnerId(missingMessage, ownerId)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.getMessage(ownerId, missingMessage)).getStatusCode());
    }

    @Test
    void guardedMoveRejectsStaleFolderOrUidBeforeWriting() {
        MailFolder inbox = folder("INBOX", "INBOX");
        MailFolder archive = folder("Archive", "ARCHIVE");
        MessageMetadata message = message(inbox, 8);
        when(folders.findByIdAndOwnerId(archive.getId(), ownerId)).thenReturn(Optional.of(archive));
        when(folders.lockById(archive.getId())).thenReturn(Optional.of(archive));
        when(messages.lockOwned(message.getId(), ownerId)).thenReturn(Optional.of(message));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.moveMessage(ownerId, message.getId(), archive.getId(), inbox.getId(), 7L)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.moveMessage(ownerId, message.getId(), archive.getId(), UUID.randomUUID(), 8L)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.moveMessage(ownerId, message.getId(), archive.getId(), inbox.getId(), null)).getStatusCode());
        verify(messages, never()).save(any());
    }

    private interface IoCall<T> {
        T run() throws IOException;
    }

    private static <T> T assertNoIo(IoCall<T> call) {
        try {
            return call.run();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private MailFolder folder(String name, String specialUse) {
        MailFolder folder = new MailFolder();
        folder.setId(UUID.randomUUID());
        folder.setOwnerId(ownerId);
        folder.setName(name);
        folder.setSpecialUse(specialUse);
        folder.setUidNext(10);
        folder.setUidValidity(42);
        folder.setHighestModseq(5);
        return folder;
    }

    private MessageMetadata message(MailFolder folder, long uid) {
        MessageMetadata message = new MessageMetadata();
        message.setId(UUID.randomUUID());
        message.setOwnerId(ownerId);
        message.setFolder(folder);
        message.setUid(uid);
        message.setModseq(1);
        message.setSender("sender@partner.test");
        message.setSubject("Subject");
        message.setSnippet("Snippet");
        message.setInternalDate(Instant.parse("2026-01-01T00:00:00Z"));
        message.setSizeBytes(123);
        message.setMaildirPath("user@company.local/INBOX/new/missing.eml");
        message.setAvStatus(MessageMetadata.AvStatus.CLEAN);
        message.setSpamScore(0.5);
        return message;
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
