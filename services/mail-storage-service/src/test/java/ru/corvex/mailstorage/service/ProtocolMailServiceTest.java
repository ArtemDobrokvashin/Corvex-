package ru.corvex.mailstorage.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.ProtocolDtos;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.MailFolder;
import ru.corvex.mailstorage.domain.MessageMetadata;
import ru.corvex.mailstorage.mime.MimeMailParser;
import ru.corvex.mailstorage.repository.MailFolderRepository;
import ru.corvex.mailstorage.repository.MessageMetadataRepository;
import ru.corvex.mailstorage.storage.MaildirStorage;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Протокольный API этапа 3: инварианты IMAP (UID под локом, частичные флаги, expunge
 * только \Deleted, квота на APPEND) проверяются без Spring-контекста — репозитории моки,
 * Maildir/spool настоящие на временной ФС.
 */
class ProtocolMailServiceTest {

    @TempDir
    Path tmp;

    private final UUID ownerId = UUID.randomUUID();

    private MailFolderRepository folders;
    private MessageMetadataRepository messages;
    private AuthUserClient auth;
    private MaildirStorage maildir;
    private ProtocolMailService service;

    @BeforeEach
    void setUp() {
        folders = mock(MailFolderRepository.class);
        messages = mock(MessageMetadataRepository.class);
        auth = mock(AuthUserClient.class);
        maildir = new MaildirStorage(tmp.resolve("mail"), tmp.resolve("att"));
        RawMessageSpool spool = new RawMessageSpool(tmp.resolve("spool"), 1_000_000);
        service = new ProtocolMailService(folders, messages, mock(MailDeliveryService.class),
                maildir, spool, new MimeMailParser(maildir), auth);
    }

    private MailFolder folder(String name, String specialUse, long uidNext) {
        MailFolder f = new MailFolder();
        f.setId(UUID.randomUUID());
        f.setOwnerId(ownerId);
        f.setName(name);
        f.setSpecialUse(specialUse);
        f.setUidNext(uidNext);
        f.setUidValidity(42);
        f.setHighestModseq(20);
        return f;
    }

    private MessageMetadata message(MailFolder folder, long uid, String maildirPath) {
        MessageMetadata m = new MessageMetadata();
        m.setId(UUID.randomUUID());
        m.setOwnerId(ownerId);
        m.setFolder(folder);
        m.setUid(uid);
        m.setModseq(uid);
        m.setSender("ivanov@corp.local");
        m.setSubject("Тема");
        m.setInternalDate(Instant.now());
        m.setSizeBytes(10);
        m.setMaildirPath(maildirPath);
        m.setAvStatus(MessageMetadata.AvStatus.CLEAN);
        return m;
    }

    @Test
    void updateFlagsTouchesOnlyNonNullFields() {
        MailFolder inbox = folder("INBOX", "INBOX", 5);
        MessageMetadata m = message(inbox, 3, "x/y.eml");
        m.setSeen(false);
        m.setFlagged(true);
        when(messages.findByFolder_IdAndUidAndOwnerId(inbox.getId(), 3L, ownerId)).thenReturn(Optional.of(m));
        when(folders.lockById(inbox.getId())).thenReturn(Optional.of(inbox));
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProtocolDtos.MessageMeta meta = service.updateFlags(ownerId, inbox.getId(), 3L,
                new ProtocolDtos.FlagsUpdate(true, null, null, null, null));

        assertTrue(meta.seen());
        assertTrue(meta.flagged(), "null-флаг не должен сбрасываться");
        assertEquals(21L, meta.modseq(), "CONDSTORE: изменение флагов должно двигать MODSEQ письма");
        assertEquals(21L, inbox.getHighestModseq());
    }

    @Test
    void expungeDeletesOnlyFlaggedDeletedAndTheirFiles() throws Exception {
        MailFolder inbox = folder("INBOX", "INBOX", 9);
        String rel = maildir.storeMessage("ivanov@corp.local", "INBOX",
                new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8))).toString();
        MessageMetadata victim = message(inbox, 7, rel);
        victim.setDeleted(true);
        when(folders.findByIdAndOwnerId(inbox.getId(), ownerId)).thenReturn(Optional.of(inbox));
        when(folders.lockById(inbox.getId())).thenReturn(Optional.of(inbox));
        when(messages.findAllByFolder_IdAndOwnerIdAndDeletedTrue(inbox.getId(), ownerId)).thenReturn(List.of(victim));

        ProtocolDtos.ExpungeResponse res = service.expunge(ownerId, inbox.getId(), new ProtocolDtos.ExpungeRequest(List.of()));

        assertEquals(List.of(7L), res.expungedUids());
        assertEquals(21L, inbox.getHighestModseq(), "EXPUNGE двигает HIGHESTMODSEQ, иначе QRESYNC не увидит удаление");
        assertFalse(Files.exists(tmp.resolve("mail").resolve(rel)), "файл .eml должен быть удалён с диска");
        verify(messages).delete(victim);
    }

    @Test
    void uidExpungeSkipsMessagesWithoutDeletedFlag() {
        MailFolder inbox = folder("INBOX", "INBOX", 9);
        MessageMetadata alive = message(inbox, 4, "x/y.eml");
        alive.setDeleted(false);
        when(folders.findByIdAndOwnerId(inbox.getId(), ownerId)).thenReturn(Optional.of(inbox));
        when(folders.lockById(inbox.getId())).thenReturn(Optional.of(inbox));
        when(messages.findByFolder_IdAndUidAndOwnerId(inbox.getId(), 4L, ownerId)).thenReturn(Optional.of(alive));

        ProtocolDtos.ExpungeResponse res = service.expunge(ownerId, inbox.getId(),
                new ProtocolDtos.ExpungeRequest(List.of(4L)));

        assertTrue(res.expungedUids().isEmpty(), "UID EXPUNGE без флага \\Deleted ничего не удаляет (RFC 4315)");
        verify(messages, never()).delete(any());
    }

    @Test
    void appendAssignsNextUidAndStoresSeenInCur() throws Exception {
        MailFolder drafts = folder("Drafts", "DRAFTS", 11);
        when(auth.findById(ownerId)).thenReturn(Optional.of(
                new AuthUserClient.MailboxOwner(ownerId, "ivanov@corp.local", "Иван Иванов", "ACTIVE", 0)));
        when(folders.findByIdAndOwnerId(drafts.getId(), ownerId)).thenReturn(Optional.of(drafts));
        when(folders.lockById(drafts.getId())).thenReturn(Optional.of(drafts));
        when(messages.sumSizeByOwnerId(ownerId)).thenReturn(0L);
        when(messages.saveAndFlush(any())).thenAnswer(inv -> {
            MessageMetadata m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
        byte[] eml = "From: ivanov@corp.local\r\nSubject: draft\r\n\r\nbody".getBytes(StandardCharsets.UTF_8);

        ProtocolDtos.AppendResponse res = service.append(ownerId, drafts.getId(),
                new ByteArrayInputStream(eml), Instant.now(), Set.of("\\seen", "\\draft"));

        assertEquals(11L, res.uid());
        assertEquals(21L, res.modseq(), "APPEND получает свежий MODSEQ из папки под тем же локом, что UID");
        assertEquals(12L, drafts.getUidNext(), "uidNext должен вырасти ровно на единицу");
        assertEquals(21L, drafts.getHighestModseq());
        Path cur = tmp.resolve("mail/ivanov@corp.local/Drafts/cur");
        try (var files = Files.list(cur)) {
            assertEquals(1, files.count(), "\\Seen APPEND должен лечь в cur/, а не new/");
        }
    }

    @Test
    void appendFailsClosedOnQuota() {
        MailFolder drafts = folder("Drafts", "DRAFTS", 1);
        when(auth.findById(ownerId)).thenReturn(Optional.of(
                new AuthUserClient.MailboxOwner(ownerId, "ivanov@corp.local", "Иван Иванов", "ACTIVE", 10)));
        when(messages.sumSizeByOwnerId(ownerId)).thenReturn(9L);
        byte[] eml = "Subject: big\r\n\r\n0123456789".getBytes(StandardCharsets.UTF_8);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.append(ownerId, drafts.getId(), new ByteArrayInputStream(eml), null, Set.of()));
        assertEquals(HttpStatus.INSUFFICIENT_STORAGE, e.getStatusCode());
    }

    @Test
    void copyCreatesNewRowWithFreshUidAndClearedDeleted() throws Exception {
        MailFolder inbox = folder("INBOX", "INBOX", 5);
        MailFolder archive = folder("Archive", "ARCHIVE", 100);
        String rel = maildir.storeMessage("ivanov@corp.local", "INBOX",
                new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8))).toString();
        MessageMetadata src = message(inbox, 2, rel);
        src.setDeleted(true);
        when(folders.findByIdAndOwnerId(inbox.getId(), ownerId)).thenReturn(Optional.of(inbox));
        when(folders.findByIdAndOwnerId(archive.getId(), ownerId)).thenReturn(Optional.of(archive));
        when(folders.lockById(archive.getId())).thenReturn(Optional.of(archive));
        when(messages.findByFolder_IdAndUidAndOwnerId(inbox.getId(), 2L, ownerId)).thenReturn(Optional.of(src));
        when(messages.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ProtocolDtos.CopyResponse res = service.copyMessages(ownerId, inbox.getId(),
                new ProtocolDtos.CopyRequest(List.of(2L), archive.getId(), false));

        assertEquals(List.of(100L), res.targetUids());
        assertEquals(21L, archive.getHighestModseq(), "копия получает свежий MODSEQ целевой папки");
        assertTrue(Files.exists(tmp.resolve("mail").resolve(rel)), "COPY не трогает исходный файл");
    }

    @Test
    void folderNameValidationRejectsSystemAndSeparators() {
        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class, () -> ProtocolMailService.validateFolderName("inbox"))
                        .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> ProtocolMailService.validateFolderName("a/b"))
                        .getStatusCode());
        assertEquals("Проекты", ProtocolMailService.validateFolderName(" Проекты "));
    }

    @Test
    void renameRewritesFolderSegmentInMaildirPath() {
        assertEquals("ivanov@corp.local/New_Name/new/1.eml",
                ProtocolMailService.replaceFolderSegment("ivanov@corp.local/Old_Name/new/1.eml", "New Name"));
    }

    @Test
    void renameCanMoveFolderToAnotherParentWithoutRenamingMaildir() throws Exception {
        MailFolder projects = folder("Проекты", null, 1);
        MailFolder archive = folder("Архив", null, 1);
        MailFolder child = folder("2026", null, 1);
        child.setParent(projects);
        when(folders.findByIdAndOwnerId(child.getId(), ownerId)).thenReturn(Optional.of(child));
        when(folders.findByIdAndOwnerId(archive.getId(), ownerId)).thenReturn(Optional.of(archive));
        when(folders.findAllByOwnerIdOrderBySpecialUseAscNameAsc(ownerId))
                .thenReturn(List.of(projects, archive, child));
        when(messages.countByFolder_Id(child.getId())).thenReturn(0L);
        when(messages.countByFolder_IdAndSeenFalse(child.getId())).thenReturn(0L);

        ProtocolDtos.FolderInfo moved = service.renameFolder(ownerId, child.getId(),
                new ProtocolDtos.RenameFolderRequest("2026", archive.getId(), false));

        assertEquals(archive.getId(), moved.parentId(), "IMAP RENAME A -> B/A меняет parent_id, каталог Maildir тот же");
        assertEquals("2026", moved.name());
        verify(auth, never()).findById(ownerId);
    }

    @Test
    void parseFlagsNormalizesCaseAndSeparators() {
        assertEquals(Set.of("\\seen", "\\draft"), ProtocolMailService.parseFlags("\\Seen, \\Draft"));
        assertTrue(ProtocolMailService.parseFlags(null).isEmpty());
    }
}
