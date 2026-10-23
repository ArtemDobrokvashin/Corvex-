package ru.corvex.mailstorage.service;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
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
import java.io.InputStream;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolMailServiceAdditionalTest {

    @TempDir
    Path tmp;

    private final UUID ownerId = UUID.randomUUID();
    private MailFolderRepository folders;
    private MessageMetadataRepository messages;
    private MailDeliveryService delivery;
    private MaildirStorage maildir;
    private RawMessageSpool spool;
    private AuthUserClient auth;
    private ProtocolMailService service;

    @BeforeEach
    void setUp() {
        folders = mock(MailFolderRepository.class);
        messages = mock(MessageMetadataRepository.class);
        delivery = mock(MailDeliveryService.class);
        maildir = new MaildirStorage(tmp.resolve("mail"), tmp.resolve("attachments"));
        spool = new RawMessageSpool(tmp.resolve("spool"), 1_000_000);
        auth = mock(AuthUserClient.class);
        service = new ProtocolMailService(folders, messages, delivery, maildir, spool,
                new MimeMailParser(maildir), auth);
        when(folders.save(any(MailFolder.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void listsAndDescribesFoldersWithCounts() {
        MailFolder inbox = folder("INBOX", "INBOX");
        MailFolder child = folder("Child", null);
        child.setParent(inbox);
        when(folders.findAllByOwnerIdOrderBySpecialUseAscNameAsc(ownerId)).thenReturn(List.of(inbox, child));
        when(folders.findByIdAndOwnerId(child.getId(), ownerId)).thenReturn(Optional.of(child));
        when(messages.countByFolder_Id(inbox.getId())).thenReturn(4L);
        when(messages.countByFolder_IdAndSeenFalse(inbox.getId())).thenReturn(1L);
        when(messages.countByFolder_Id(child.getId())).thenReturn(2L);
        when(messages.countByFolder_IdAndSeenFalse(child.getId())).thenReturn(2L);

        var all = service.listFolders(ownerId);
        var status = service.folderStatus(ownerId, child.getId());

        assertEquals(2, all.size());
        assertEquals(inbox.getId(), status.parentId());
        assertEquals(2, status.unseenCount());
        verify(delivery, times(6)).ensureFolder(eq(ownerId), anyString(), anyString());
    }

    @Test
    void createFolderValidatesParentAndSiblingUniqueness() {
        MailFolder parent = folder("Parent", null);
        when(folders.findByIdAndOwnerId(parent.getId(), ownerId)).thenReturn(Optional.of(parent));
        when(folders.existsSibling(ownerId, parent.getId(), "Child")).thenReturn(false);
        when(folders.saveAndFlush(any(MailFolder.class))).thenAnswer(inv -> {
            MailFolder created = inv.getArgument(0);
            created.setId(UUID.randomUUID());
            return created;
        });

        var created = service.createFolder(ownerId,
                new ProtocolDtos.CreateFolderRequest(" Child ", parent.getId()));

        assertEquals("Child", created.name());
        assertEquals(parent.getId(), created.parentId());
        assertEquals(1, created.uidNext());
        assertTrue(created.uidValidity() >= 1);

        when(folders.existsSibling(ownerId, null, "Existing")).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.createFolder(ownerId, new ProtocolDtos.CreateFolderRequest("Existing", null)))
                .getStatusCode());
    }

    @Test
    void renameActuallyRenamesDirectoryAndMessagePaths() throws Exception {
        MailFolder folder = folder("Projects", null);
        String rel = maildir.storeMessage("user@company.local", "Projects", bytes("body")).toString();
        MessageMetadata message = message(folder, 1, rel);
        when(folders.findByIdAndOwnerId(folder.getId(), ownerId)).thenReturn(Optional.of(folder));
        when(auth.findById(ownerId)).thenReturn(Optional.of(owner()));
        when(messages.findAllByFolder_IdAndOwnerId(folder.getId(), ownerId)).thenReturn(List.of(message));
        when(messages.countByFolder_Id(folder.getId())).thenReturn(1L);

        var renamed = service.renameFolder(ownerId, folder.getId(),
                new ProtocolDtos.RenameFolderRequest("Renamed Folder", null, false));

        assertEquals("Renamed Folder", renamed.name());
        assertTrue(message.getMaildirPath().contains("Renamed_Folder"));
        assertTrue(Files.exists(tmp.resolve("mail").resolve(message.getMaildirPath())));
        verify(messages).save(message);
        verify(folders).save(folder);
    }

    @Test
    void renameGuardsSystemCycleSiblingAndCanMoveToRoot() throws Exception {
        MailFolder system = folder("INBOX", "INBOX");
        when(folders.findByIdAndOwnerId(system.getId(), ownerId)).thenReturn(Optional.of(system));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.renameFolder(ownerId, system.getId(),
                        new ProtocolDtos.RenameFolderRequest("Other", null, false))).getStatusCode());

        MailFolder parent = folder("Parent", null);
        MailFolder child = folder("Child", null);
        child.setParent(parent);
        when(folders.findByIdAndOwnerId(parent.getId(), ownerId)).thenReturn(Optional.of(parent));
        when(folders.findByIdAndOwnerId(child.getId(), ownerId)).thenReturn(Optional.of(child));
        when(folders.findAllByOwnerIdOrderBySpecialUseAscNameAsc(ownerId)).thenReturn(List.of(parent, child));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.renameFolder(ownerId, parent.getId(),
                        new ProtocolDtos.RenameFolderRequest("Parent", child.getId(), false))).getStatusCode());

        MailFolder movable = folder("Movable", null);
        movable.setParent(parent);
        when(folders.findByIdAndOwnerId(movable.getId(), ownerId)).thenReturn(Optional.of(movable));
        when(folders.existsSibling(ownerId, null, "Movable")).thenReturn(false);
        var moved = service.renameFolder(ownerId, movable.getId(),
                new ProtocolDtos.RenameFolderRequest("Movable", child.getId(), true));
        assertNull(moved.parentId());

        when(folders.existsSibling(ownerId, null, "Taken")).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.renameFolder(ownerId, movable.getId(),
                        new ProtocolDtos.RenameFolderRequest("Taken", null, true))).getStatusCode());
    }

    @Test
    void renameWithoutChangesDoesNotPersistFolder() throws Exception {
        MailFolder folder = folder("Same", null);
        when(folders.findByIdAndOwnerId(folder.getId(), ownerId)).thenReturn(Optional.of(folder));

        var result = service.renameFolder(ownerId, folder.getId(),
                new ProtocolDtos.RenameFolderRequest("Same", null, false));

        assertEquals("Same", result.name());
        verify(folders, never()).save(folder);
        verify(auth, never()).findById(ownerId);
    }

    @Test
    void deleteFolderGuardsAndSuccessPathRemoveRowsFilesAndDirectory() throws Exception {
        MailFolder system = folder("INBOX", "INBOX");
        when(folders.findByIdAndOwnerId(system.getId(), ownerId)).thenReturn(Optional.of(system));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.deleteFolder(ownerId, system.getId())).getStatusCode());

        MailFolder parent = folder("Parent", null);
        when(folders.findByIdAndOwnerId(parent.getId(), ownerId)).thenReturn(Optional.of(parent));
        when(folders.existsByParent_Id(parent.getId())).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.deleteFolder(ownerId, parent.getId())).getStatusCode());

        MailFolder disposable = folder("Disposable", null);
        String rel = maildir.storeMessage("user@company.local", "Disposable", bytes("delete")).toString();
        MessageMetadata message = message(disposable, 1, rel);
        when(folders.findByIdAndOwnerId(disposable.getId(), ownerId)).thenReturn(Optional.of(disposable));
        when(folders.existsByParent_Id(disposable.getId())).thenReturn(false);
        when(messages.findAllByFolder_IdAndOwnerId(disposable.getId(), ownerId)).thenReturn(List.of(message));
        when(auth.findById(ownerId)).thenReturn(Optional.of(owner()));

        service.deleteFolder(ownerId, disposable.getId());

        verify(messages).delete(message);
        verify(folders).delete(disposable);
        assertFalse(Files.exists(tmp.resolve("mail/user@company.local/Disposable")));
    }

    @Test
    void listMessagesClampsLimitsAndAfterUid() {
        MailFolder inbox = folder("INBOX", "INBOX");
        MessageMetadata message = message(inbox, 3, "x");
        when(folders.findByIdAndOwnerId(inbox.getId(), ownerId)).thenReturn(Optional.of(inbox));
        when(messages.findByFolder_IdAndOwnerIdAndUidGreaterThanOrderByUidAsc(
                eq(inbox.getId()), eq(ownerId), anyLong(), any(Pageable.class))).thenReturn(List.of(message));

        assertEquals(3, service.listMessages(ownerId, inbox.getId(), -5, 0).get(0).uid());
        assertEquals(3, service.listMessages(ownerId, inbox.getId(), 2, 999_999).get(0).uid());

        ArgumentCaptor<Long> after = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(messages, times(2)).findByFolder_IdAndOwnerIdAndUidGreaterThanOrderByUidAsc(
                eq(inbox.getId()), eq(ownerId), after.capture(), pages.capture());
        assertEquals(List.of(0L, 2L), after.getAllValues());
        assertEquals(List.of(1000, 5000), pages.getAllValues().stream().map(Pageable::getPageSize).toList());
    }

    @Test
    void opensRawMessagesByUidAndIdAndReportsMissing() throws Exception {
        MailFolder inbox = folder("INBOX", "INBOX");
        String rel = maildir.storeMessage("user@company.local", "INBOX", bytes("raw body")).toString();
        MessageMetadata message = message(inbox, 9, rel);
        when(messages.findByFolder_IdAndUidAndOwnerId(inbox.getId(), 9L, ownerId)).thenReturn(Optional.of(message));
        when(messages.findByIdAndOwnerId(message.getId(), ownerId)).thenReturn(Optional.of(message));

        try (InputStream in = service.openByUid(ownerId, inbox.getId(), 9).stream()) {
            assertEquals("raw body", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (InputStream in = service.openById(ownerId, message.getId()).stream()) {
            assertEquals("raw body", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        when(messages.findByFolder_IdAndUidAndOwnerId(inbox.getId(), 10L, ownerId)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.openByUid(ownerId, inbox.getId(), 10)).getStatusCode());
    }

    @Test
    void flagUpdateWithoutChangesStillSavesWithoutLock() {
        MailFolder inbox = folder("INBOX", "INBOX");
        MessageMetadata message = message(inbox, 3, "x");
        when(messages.findByFolder_IdAndUidAndOwnerId(inbox.getId(), 3L, ownerId)).thenReturn(Optional.of(message));
        when(messages.save(any(MessageMetadata.class))).thenAnswer(inv -> inv.getArgument(0));

        var meta = service.updateFlags(ownerId, inbox.getId(), 3,
                new ProtocolDtos.FlagsUpdate(false, false, false, false, false));

        assertEquals(1, meta.modseq());
        verify(folders, never()).lockById(any());
        verify(messages).save(message);
    }

    @Test
    void expungeAcceptsNullRequestAsClassicExpunge() {
        MailFolder inbox = folder("INBOX", "INBOX");
        MessageMetadata deleted = message(inbox, 6, "user@company.local/INBOX/new/missing.eml");
        deleted.setDeleted(true);
        when(folders.findByIdAndOwnerId(inbox.getId(), ownerId)).thenReturn(Optional.of(inbox));
        when(folders.lockById(inbox.getId())).thenReturn(Optional.of(inbox));
        when(messages.findAllByFolder_IdAndOwnerIdAndDeletedTrue(inbox.getId(), ownerId)).thenReturn(List.of(deleted));

        assertEquals(List.of(6L), service.expunge(ownerId, inbox.getId(), null).expungedUids());
        verify(messages).delete(deleted);
    }

    @Test
    void hardDeleteAdvancesFolderModseqAndHandlesFolderlessRow() {
        MailFolder inbox = folder("INBOX", "INBOX");
        MessageMetadata withFolder = message(inbox, 2, "user@company.local/INBOX/new/missing.eml");
        when(messages.findByIdAndOwnerId(withFolder.getId(), ownerId)).thenReturn(Optional.of(withFolder));
        when(folders.lockById(inbox.getId())).thenReturn(Optional.of(inbox));

        service.hardDeleteMessage(ownerId, withFolder.getId());
        assertEquals(21, inbox.getHighestModseq());

        MessageMetadata folderless = message(inbox, 3, "user@company.local/INBOX/new/missing.eml");
        folderless.setFolder(null);
        when(messages.findByIdAndOwnerId(folderless.getId(), ownerId)).thenReturn(Optional.of(folderless));
        service.hardDeleteMessage(ownerId, folderless.getId());
        verify(messages).delete(folderless);
    }

    @Test
    void appendMapsTooLargeAndBrokenMimeAndCleansSpool() throws Exception {
        when(auth.findById(ownerId)).thenReturn(Optional.of(owner()));
        ProtocolMailService small = new ProtocolMailService(folders, messages, delivery, maildir,
                new RawMessageSpool(tmp.resolve("small"), 3), new MimeMailParser(maildir), auth);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, assertThrows(ResponseStatusException.class,
                () -> small.append(ownerId, UUID.randomUUID(), bytes("1234"), null, Set.of())).getStatusCode());

        MimeMailParser broken = mock(MimeMailParser.class);
        when(broken.parse(any(InputStream.class), eq(false))).thenThrow(new MessagingException("bad"));
        when(messages.sumSizeByOwnerId(ownerId)).thenReturn(0L);
        ProtocolMailService local = new ProtocolMailService(
                folders, messages, delivery, maildir, spool, broken, auth);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> local.append(ownerId, UUID.randomUUID(), bytes("raw"), null, Set.of())).getStatusCode());
    }

    @Test
    void copyWithMoveUpdatesOriginalAndNullUidListIsEmpty() throws Exception {
        MailFolder inbox = folder("INBOX", "INBOX");
        MailFolder archive = folder("Archive", "ARCHIVE");
        archive.setUidNext(50);
        String rel = maildir.storeMessage("user@company.local", "INBOX", bytes("move")).toString();
        MessageMetadata source = message(inbox, 4, rel);
        when(folders.findByIdAndOwnerId(inbox.getId(), ownerId)).thenReturn(Optional.of(inbox));
        when(folders.findByIdAndOwnerId(archive.getId(), ownerId)).thenReturn(Optional.of(archive));
        when(folders.lockById(archive.getId())).thenReturn(Optional.of(archive));
        when(messages.findByFolder_IdAndUidAndOwnerId(inbox.getId(), 4L, ownerId)).thenReturn(Optional.of(source));

        var moved = service.copyMessages(ownerId, inbox.getId(),
                new ProtocolDtos.CopyRequest(List.of(4L), archive.getId(), true));

        assertEquals(List.of(50L), moved.targetUids());
        assertEquals(archive, source.getFolder());
        assertEquals(50, source.getUid());
        assertFalse(Files.exists(tmp.resolve("mail").resolve(rel)));
        assertTrue(Files.exists(tmp.resolve("mail").resolve(source.getMaildirPath())));
        verify(messages).save(source);

        var empty = service.copyMessages(ownerId, inbox.getId(),
                new ProtocolDtos.CopyRequest(null, archive.getId(), false));
        assertTrue(empty.sourceUids().isEmpty() && empty.targetUids().isEmpty());

        when(messages.findByFolder_IdAndUidAndOwnerId(inbox.getId(), 404L, ownerId)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.copyMessages(ownerId, inbox.getId(),
                        new ProtocolDtos.CopyRequest(List.of(404L), archive.getId(), false))).getStatusCode());
    }

    @Test
    void quotaUsesAuthOwnerAndRepositoryAggregates() {
        when(auth.findById(ownerId)).thenReturn(Optional.of(
                new AuthUserClient.MailboxOwner(ownerId, "user@company.local", "User", "ACTIVE", 999)));
        when(messages.sumSizeByOwnerId(ownerId)).thenReturn(123L);
        when(messages.countByOwnerId(ownerId)).thenReturn(7L);

        var quota = service.quota(ownerId);

        assertEquals(123, quota.usedBytes());
        assertEquals(999, quota.quotaBytes());
        assertEquals(7, quota.messageCount());

        UUID missingOwner = UUID.randomUUID();
        when(auth.findById(missingOwner)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> service.quota(missingOwner)).getStatusCode());
    }

    @Test
    void staticHelpersCoverRemainingEdgeCases() {
        assertNull(ProtocolMailService.replaceFolderSegment(null, "New"));
        assertEquals("only/two", ProtocolMailService.replaceFolderSegment("only/two", "New"));
        assertEquals("box/New_Folder/new/x.eml",
                ProtocolMailService.replaceFolderSegment("box\\Old\\new\\x.eml", "New Folder"));
        assertTrue(ProtocolMailService.parseFlags(" ").isEmpty());
        assertEquals(Set.of("\\seen", "\\answered"), ProtocolMailService.parseFlags(" \\Seen\t,\\Answered "));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> ProtocolMailService.validateFolderName(null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> ProtocolMailService.validateFolderName("bad*name")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> ProtocolMailService.validateFolderName("bad%name")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> ProtocolMailService.validateFolderName("bad..name")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> ProtocolMailService.validateFolderName("x".repeat(257))).getStatusCode());

        MessageMetadata meta = message(folder("INBOX", "INBOX"), 12, "x");
        meta.setSeen(true);
        meta.setDraft(true);
        ProtocolDtos.MessageMeta dto = ProtocolMailService.toMeta(meta);
        assertEquals(12, dto.uid());
        assertTrue(dto.seen() && dto.draft());
        verify(folders, never()).existsSibling(eq(ownerId), isNull(), eq("unused"));
    }

    private AuthUserClient.MailboxOwner owner() {
        return new AuthUserClient.MailboxOwner(ownerId, "user@company.local", "User", "ACTIVE", 0);
    }

    private MailFolder folder(String name, String specialUse) {
        MailFolder folder = new MailFolder();
        folder.setId(UUID.randomUUID());
        folder.setOwnerId(ownerId);
        folder.setName(name);
        folder.setSpecialUse(specialUse);
        folder.setUidNext(10);
        folder.setUidValidity(42);
        folder.setHighestModseq(20);
        return folder;
    }

    private MessageMetadata message(MailFolder folder, long uid, String path) {
        MessageMetadata message = new MessageMetadata();
        message.setId(UUID.randomUUID());
        message.setOwnerId(ownerId);
        message.setFolder(folder);
        message.setUid(uid);
        message.setModseq(1);
        message.setMessageIdHeader("<m@x>");
        message.setSender("sender@partner.test");
        message.setSubject("Subject");
        message.setInternalDate(Instant.now());
        message.setSizeBytes(10);
        message.setMaildirPath(path);
        message.setAvStatus(MessageMetadata.AvStatus.CLEAN);
        return message;
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
