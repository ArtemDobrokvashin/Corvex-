package ru.corvex.mailimap.server;

import org.apache.james.core.Username;
import org.apache.james.events.Event;
import org.apache.james.events.EventBus;
import org.apache.james.events.RegistrationKey;
import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.ModSeq;
import org.apache.james.mailbox.exception.AnnotationException;
import org.apache.james.mailbox.exception.BadCredentialsException;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.exception.MailboxExistsException;
import org.apache.james.mailbox.exception.MailboxNotFoundException;
import org.apache.james.mailbox.exception.UnsupportedRightException;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MailboxACL;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxMetaData;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.MultimailboxesSearchQuery;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.mailbox.model.ThreadId;
import org.apache.james.mailbox.model.UidValidity;
import org.apache.james.mailbox.model.UpdatedFlags;
import org.apache.james.mailbox.model.search.MailboxQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.corvex.mailimap.client.ImapAuthClient;
import ru.corvex.mailimap.client.ImapStorageClient;

import javax.mail.Flags;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Оставшиеся ветки James MailboxManager-адаптера: auth, folders, events, rights и multi-search. */
class CorvexMailboxManagerExtendedTest {

    private static final String EMAIL = "ivanov@corp.local";
    private static final Username USER = Username.of(EMAIL);
    private static final Username OTHER = Username.of("petrov@corp.local");
    private static final Instant DATE = Instant.parse("2026-07-14T07:30:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID inboxId = UUID.randomUUID();
    private final UUID sentId = UUID.randomUUID();
    private final UUID projectsId = UUID.randomUUID();
    private final UUID childId = UUID.randomUUID();
    private final UUID archiveId = UUID.randomUUID();

    private ImapAuthClient auth;
    private ImapStorageClient storage;
    private EventBus eventBus;
    private CorvexMailboxManager manager;
    private MailboxSession session;

    @BeforeEach
    void setUp() throws Exception {
        auth = mock(ImapAuthClient.class);
        storage = mock(ImapStorageClient.class);
        eventBus = mock(EventBus.class);
        manager = new CorvexMailboxManager(auth, storage, eventBus);
        when(auth.findActiveByUsername(EMAIL)).thenReturn(Optional.of(authUser()));
        session = manager.createSystemSession(USER);
        when(storage.listFolders(userId)).thenReturn(folders());
    }

    private ImapAuthClient.AuthenticatedUser authUser() {
        return new ImapAuthClient.AuthenticatedUser(userId, EMAIL, "Ivanov");
    }

    private List<ImapStorageClient.FolderInfo> folders() {
        return List.of(
                folder(inboxId, null, "INBOX", "INBOX", 4, 11, 7, 3, 1),
                folder(sentId, null, "Sent", "SENT", 1, 12, 1, 0, 0),
                folder(projectsId, null, "Проекты", null, 1, 13, 2, 0, 0),
                folder(childId, projectsId, "2026", null, 3, 14, 2, 2, 2),
                folder(archiveId, null, "Archive", null, 1, 15, 0, 0, 0));
    }

    private static ImapStorageClient.FolderInfo folder(UUID id, UUID parent, String name, String special,
                                                       long uidNext, long validity, long highest,
                                                       long count, long unseen) {
        return new ImapStorageClient.FolderInfo(id, parent, name, special,
                uidNext, validity, highest, count, unseen);
    }

    private static ImapStorageClient.MessageMeta meta(long uid, String subject) {
        return new ImapStorageClient.MessageMeta(UUID.randomUUID(), uid, uid + 10, "<m" + uid + "@corp.local>",
                "petrov@corp.local", subject, DATE, 100 + uid,
                uid % 2 == 0, false, false, false, false);
    }

    private MailboxPath path(String name) {
        return MailboxPath.forUser(USER, name);
    }

    // ---------------------------------------------------------------------- auth

    @Test
    void capabilitiesFactoriesAndRequestLifecycleAreStable() {
        assertEquals(EnumSet.of(
                        MailboxManager.MailboxCapabilities.Move,
                        MailboxManager.MailboxCapabilities.Namespace,
                        MailboxManager.MailboxCapabilities.Quota),
                manager.getSupportedMailboxCapabilities());
        assertTrue(manager.hasCapability(MailboxManager.MailboxCapabilities.Quota));
        assertFalse(manager.hasCapability(MailboxManager.MailboxCapabilities.ACL));
        assertEquals(EnumSet.of(MailboxManager.MessageCapabilities.UniqueID),
                manager.getSupportedMessageCapabilities());
        assertEquals(EnumSet.of(
                        MailboxManager.SearchCapabilities.MultimailboxSearch,
                        MailboxManager.SearchCapabilities.PartialEmailMatch,
                        MailboxManager.SearchCapabilities.Text),
                manager.getSupportedSearchCapabilities());
        assertFalse(manager.getSupportedSearchCapabilities()
                .contains(MailboxManager.SearchCapabilities.FullText));

        MessageId first = manager.getMessageIdFactory().generate();
        MessageId second = manager.getMessageIdFactory().generate();
        assertNotEquals(first.serialize(), second.serialize());
        assertDoesNotThrow(() -> manager.startProcessingRequest(session));
        assertDoesNotThrow(() -> manager.endProcessingRequest(session));
        assertSame(storage, manager.storage());
    }

    @Test
    void passwordAuthorizationSupportsOnlySameUserAndMatchingPredicate() throws Exception {
        CorvexMailboxManager fresh = new CorvexMailboxManager(auth, storage, eventBus);
        when(auth.authenticate(EMAIL, "secret")).thenReturn(Optional.of(authUser()));
        var step = fresh.authenticate(USER, "secret");

        MailboxSession own = step.as(USER);
        assertEquals(USER, own.getUser());
        assertEquals(Optional.of(USER), own.getLoggedInUser());
        assertEquals(MailboxSession.SessionType.User, own.getType());
        assertEquals('/', own.getPathDelimiter());
        assertEquals(userId, fresh.userId(own));
        MailboxSession another = step.forMatchingUser(candidate -> candidate.equals(USER));
        assertNotEquals(own.getSessionId(), another.getSessionId());

        assertThrows(BadCredentialsException.class, () -> step.as(OTHER));
        assertThrows(BadCredentialsException.class, () -> step.as(null));
        assertThrows(BadCredentialsException.class, () -> step.forMatchingUser(null));
        assertThrows(BadCredentialsException.class, () -> step.forMatchingUser(candidate -> false));

        when(auth.authenticate(EMAIL, "")).thenReturn(Optional.of(authUser()));
        assertEquals(userId, fresh.userId(fresh.authenticate(USER, (String) null).withoutDelegation()),
                "null-пароль нормализуется в пустую строку для auth-service");

        IOException backend = new IOException("auth down");
        when(auth.authenticate(EMAIL, "broken")).thenThrow(backend);
        MailboxException error = assertThrows(MailboxException.class,
                () -> fresh.authenticate(USER, "broken").withoutDelegation());
        assertSame(backend, error.getCause());
    }

    @Test
    void preAuthenticatedAuthorizationRequiresAnActiveUser() throws Exception {
        ImapAuthClient freshAuth = mock(ImapAuthClient.class);
        CorvexMailboxManager fresh = new CorvexMailboxManager(freshAuth, storage, eventBus);
        when(freshAuth.findActiveByUsername(EMAIL)).thenReturn(Optional.of(authUser()));
        var step = fresh.authenticate(USER);
        assertEquals(userId, fresh.userId(step.withoutDelegation()));
        assertEquals(userId, fresh.userId(step.as(USER)));
        assertEquals(userId, fresh.userId(step.forMatchingUser(candidate -> true)));
        assertThrows(BadCredentialsException.class, () -> step.as(OTHER));
        assertThrows(BadCredentialsException.class, () -> step.forMatchingUser(null));
        assertThrows(BadCredentialsException.class, () -> step.forMatchingUser(candidate -> false));

        when(freshAuth.findActiveByUsername(EMAIL)).thenReturn(Optional.empty());
        assertThrows(BadCredentialsException.class, () -> step.withoutDelegation());
        IOException backend = new IOException("auth down");
        when(freshAuth.findActiveByUsername(EMAIL)).thenThrow(backend);
        MailboxException error = assertThrows(MailboxException.class, () -> step.withoutDelegation());
        assertSame(backend, error.getCause());
    }

    @Test
    void systemSessionCachesLookupAndUnresolvedUserFailsOnlyWhenIdIsNeeded() throws Exception {
        assertEquals(MailboxSession.SessionType.System, session.getType());
        assertEquals(MailboxSession.SYSTEM_SESSION_ID, session.getSessionId().getValue());
        assertTrue(session.getLoggedInUser().isEmpty());
        assertEquals(userId, manager.userId(session));
        MailboxSession anotherSystem = manager.createSystemSession(Username.of("IVANOV@corp.local"));
        assertEquals(userId, manager.userId(anotherSystem));
        verify(auth, times(1)).findActiveByUsername(EMAIL);

        ImapAuthClient missingAuth = mock(ImapAuthClient.class);
        when(missingAuth.findActiveByUsername(EMAIL)).thenReturn(Optional.empty());
        CorvexMailboxManager missing = new CorvexMailboxManager(missingAuth, storage, eventBus);
        MailboxSession unresolved = missing.createSystemSession(USER);
        assertEquals(USER, unresolved.getUser());
        assertThrows(MailboxException.class, () -> missing.userId(unresolved));

        ImapAuthClient brokenAuth = mock(ImapAuthClient.class);
        when(brokenAuth.findActiveByUsername(EMAIL)).thenThrow(new IOException("auth down"));
        CorvexMailboxManager broken = new CorvexMailboxManager(brokenAuth, storage, eventBus);
        MailboxSession brokenSession = broken.createSystemSession(USER);
        assertThrows(MailboxException.class, () -> broken.userId(brokenSession),
                "IOException разрешения не маскируется под успешную сессию при первом storage-вызове");
    }

    // ------------------------------------------------------------------ helpers

    @Test
    void idParsingFolderResolutionAndStorageFailureMappingArePrecise() throws Exception {
        UUID genericMailbox = UUID.randomUUID();
        MailboxId genericMailboxId = mock(MailboxId.class);
        when(genericMailboxId.serialize()).thenReturn(genericMailbox.toString());
        assertEquals(inboxId, CorvexMailboxManager.parseMailboxId(CorvexMailboxId.of(inboxId)));
        assertEquals(genericMailbox, CorvexMailboxManager.parseMailboxId(genericMailboxId));

        UUID genericMessage = UUID.randomUUID();
        MessageId genericMessageId = mock(MessageId.class);
        when(genericMessageId.serialize()).thenReturn(genericMessage.toString());
        assertEquals(genericMessage, CorvexMailboxManager.parseMessageId(genericMessageId));
        assertEquals(genericMessage,
                CorvexMailboxManager.parseMessageId(CorvexMessageId.of(genericMessage)));

        var folders = folders();
        assertEquals(childId, manager.resolveFolder(session, path("Проекты/2026"), folders).id());
        assertThrows(MailboxNotFoundException.class,
                () -> manager.resolveFolder(session, MailboxPath.forUser(OTHER, "INBOX"), folders));
        assertThrows(MailboxNotFoundException.class,
                () -> manager.resolveFolder(session, path("Missing"), folders));
        assertEquals(sentId, manager.resolveFolderById(session, CorvexMailboxId.of(sentId), folders).id());
        assertThrows(MailboxNotFoundException.class,
                () -> manager.resolveFolderById(session, CorvexMailboxId.of(UUID.randomUUID()), folders));

        var zeroValidity = folder(archiveId, null, "Archive", null, 1, 0, 0, 0, 0);
        Mailbox mailbox = manager.toMailbox(session, zeroValidity, List.of(zeroValidity));
        assertEquals(UidValidity.of(1), mailbox.getUidValidity());
        assertSame(MailboxACL.OWNER_FULL_ACL, mailbox.getACL());

        var notFound = CorvexMailboxManager.wrapStorage(new ImapStorageClient.NotFoundException("gone"));
        assertTrue(notFound instanceof MailboxNotFoundException);
        assertTrue(notFound.getMessage().contains("gone"));
        IOException backend = new IOException("down");
        var wrapped = CorvexMailboxManager.wrapStorage(backend);
        assertEquals(MailboxException.class, wrapped.getClass());
        assertSame(backend, wrapped.getCause());

        when(storage.folderStatus(userId, inboxId)).thenReturn(folders.get(0));
        assertEquals(inboxId, manager.folderStatus(session, inboxId).id());
        when(storage.listFolders(userId)).thenThrow(backend);
        assertThrows(MailboxException.class, () -> manager.listFolders(session));
        when(storage.folderStatus(userId, inboxId)).thenThrow(new ImapStorageClient.NotFoundException("gone"));
        assertThrows(MailboxNotFoundException.class, () -> manager.folderStatus(session, inboxId));
    }

    @Test
    void messagesArePagedByLastUidSortedAndFilteredByRange() throws Exception {
        List<ImapStorageClient.MessageMeta> page = new ArrayList<>();
        for (long uid = 2; uid <= 1001; uid++) {
            page.add(meta(uid, "Bulk"));
        }
        var first = meta(1, "First after page boundary");
        when(storage.listMessages(userId, inboxId, 0, 1000)).thenReturn(page);
        when(storage.listMessages(userId, inboxId, 1001, 1000)).thenReturn(List.of(first));

        List<ImapStorageClient.MessageMeta> all = manager.listAllMessages(session, inboxId);
        assertEquals(1001, all.size());
        assertEquals(1, all.get(0).uid());
        assertEquals(1001, all.get(all.size() - 1).uid());
        verify(storage).listMessages(userId, inboxId, 0, 1000);
        verify(storage).listMessages(userId, inboxId, 1001, 1000);

        when(storage.listMessages(userId, sentId, 0, 1000))
                .thenReturn(List.of(meta(3, "C"), meta(1, "A"), meta(2, "B")));
        assertEquals(List.of(2L), manager.listMessagesInRange(
                session, sentId, MessageRange.one(MessageUid.of(2))).stream()
                .map(ImapStorageClient.MessageMeta::uid).toList());
        assertEquals(List.of(1L, 2L, 3L), manager.listMessagesInRange(
                session, sentId, MessageRange.all()).stream()
                .map(ImapStorageClient.MessageMeta::uid).toList());

        byte[] raw = "Subject: Raw\r\n\r\nBody".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var rawMeta = meta(9, "Raw");
        when(storage.readRawByUid(userId, inboxId, 9)).thenReturn(raw);
        assertArrayEquals(raw, manager.readRaw(session, inboxId, rawMeta));
        when(storage.readRawByUid(userId, inboxId, 9)).thenThrow(new IOException("raw down"));
        assertThrows(MailboxException.class, () -> manager.readRaw(session, inboxId, rawMeta));
    }

    @Test
    void eventDispatchToleratesMissingBusNullNoopPublisherErrorsAndSyncThrows() throws Exception {
        MailboxId mailboxId = CorvexMailboxId.of(inboxId);
        CorvexMailboxManager withoutBus = new CorvexMailboxManager(auth, storage, null);
        Event event = mock(Event.class);
        when(event.isNoop()).thenReturn(false);
        assertDoesNotThrow(() -> withoutBus.dispatch(session, event, mailboxId));
        assertDoesNotThrow(() -> manager.dispatch(session, null, mailboxId));

        Event noop = mock(Event.class);
        when(noop.isNoop()).thenReturn(true);
        manager.dispatch(session, noop, mailboxId);
        verify(eventBus, never()).dispatch(any(Event.class), any(RegistrationKey.class));

        manager.dispatch(session, event, mailboxId); // mock publisher = null, допустимо для адаптера
        when(eventBus.dispatch(any(Event.class), any(RegistrationKey.class)))
                .thenReturn(Mono.error(new IllegalStateException("listener failed")));
        assertDoesNotThrow(() -> manager.dispatch(session, event, mailboxId));
        when(eventBus.dispatch(any(Event.class), any(RegistrationKey.class)))
                .thenThrow(new IllegalStateException("bus failed"));
        assertDoesNotThrow(() -> manager.dispatch(session, event, mailboxId));

        EventBus recordingBus = mock(EventBus.class);
        when(recordingBus.dispatch(any(Event.class), any(RegistrationKey.class))).thenReturn(Mono.empty());
        CorvexMailboxManager recording = new CorvexMailboxManager(auth, storage, recordingBus);
        recording.dispatchAdded(session, path("INBOX"), mailboxId, List.of(), false);
        recording.dispatchExpunged(session, path("INBOX"), mailboxId, List.of());
        recording.dispatchFlagsUpdated(session, path("INBOX"), mailboxId, List.of());
        verify(recordingBus, never()).dispatch(any(Event.class), any(RegistrationKey.class));

        var added = meta(1, "Added");
        recording.dispatchAdded(session, path("INBOX"), mailboxId, List.of(added), true);
        recording.dispatchExpunged(session, path("INBOX"), mailboxId, List.of(added));
        recording.dispatchFlagsUpdated(session, path("INBOX"), mailboxId, List.of(UpdatedFlags.builder()
                .uid(MessageUid.of(1))
                .messageId(CorvexMessageId.of(added.id()))
                .modSeq(ModSeq.of(11))
                .oldFlags(new Flags())
                .newFlags(new Flags(Flags.Flag.SEEN))
                .build()));
        ArgumentCaptor<Event> events = ArgumentCaptor.forClass(Event.class);
        verify(recordingBus, times(3)).dispatch(events.capture(), any(RegistrationKey.class));
        assertEquals(List.of("Added", "Expunged", "FlagsUpdated"), events.getAllValues().stream()
                .map(e -> e.getClass().getSimpleName()).toList());
    }

    // ------------------------------------------------------------------ folders

    @Test
    void getMailboxSupportsEntityAndReactiveOverloadsAndReportsMissing() throws Exception {
        Mailbox entity = manager.toMailbox(session, folders().get(3), folders());
        MessageManager byEntity = manager.getMailbox(entity, session);
        assertEquals(CorvexMailboxId.of(childId), byEntity.getId());
        assertEquals("Проекты/2026", byEntity.getMailboxPath().getName());

        assertEquals(CorvexMailboxId.of(inboxId),
                Mono.from(manager.getMailboxReactive(path("INBOX"), session)).block().getId());
        assertEquals(CorvexMailboxId.of(sentId),
                Mono.from(manager.getMailboxReactive(CorvexMailboxId.of(sentId), session)).block().getId());
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> Mono.from(manager.getMailboxReactive(path("Missing"), session)).block());
        assertTrue(error.getCause() instanceof MailboxNotFoundException);
    }

    @Test
    void createMailboxRejectsBlankExistingAndStorageFailureAndReusesExistingParent() throws Exception {
        assertTrue(manager.createMailbox(path(""), MailboxManager.CreateOption.NONE, session).isEmpty());
        assertThrows(MailboxExistsException.class,
                () -> manager.createMailbox(path("inbox"), MailboxManager.CreateOption.NONE, session));
        verify(storage, never()).createFolder(any(), any(), any());

        UUID createdId = UUID.randomUUID();
        var created = folder(createdId, projectsId, "2027", null, 1, 99, 0, 0, 0);
        when(storage.createFolder(userId, "2027", projectsId)).thenReturn(created);
        Optional<MailboxId> result = manager.createMailbox(
                path("Проекты/2027"), MailboxManager.CreateOption.CREATE_SUBSCRIPTION, session);
        assertEquals(Optional.of(CorvexMailboxId.of(createdId)), result);
        verify(storage, times(1)).createFolder(any(), any(), any());

        when(storage.createFolder(userId, "NewRoot", null)).thenThrow(new IOException("create down"));
        assertThrows(MailboxException.class,
                () -> manager.createMailbox(path("NewRoot"), MailboxManager.CreateOption.NONE, session));
    }

    @Test
    void deleteMailboxByPathAndIdAndStorageNotFound() throws Exception {
        manager.deleteMailbox(path("Sent"), session);
        verify(storage).deleteFolder(userId, sentId);

        Mailbox deleted = manager.deleteMailbox(CorvexMailboxId.of(childId), session);
        assertEquals(CorvexMailboxId.of(childId), deleted.getMailboxId());
        assertEquals("Проекты/2026", deleted.generateAssociatedPath().getName());
        verify(storage).deleteFolder(userId, childId);

        assertThrows(MailboxNotFoundException.class, () -> manager.deleteMailbox(path("Missing"), session));
        org.mockito.Mockito.doThrow(new ImapStorageClient.NotFoundException("already gone"))
                .when(storage).deleteFolder(userId, archiveId);
        assertThrows(MailboxNotFoundException.class, () -> manager.deleteMailbox(path("Archive"), session));
    }

    @Test
    void renameMailboxMovesSubtreeToRootAndSupportsIdOverload() throws Exception {
        var renamedProjects = folder(projectsId, null, "NewProjects", null, 1, 13, 2, 0, 0);
        var childAfter = folder(childId, projectsId, "2026", null, 3, 14, 2, 2, 2);
        List<ImapStorageClient.FolderInfo> after = List.of(
                folders().get(0), folders().get(1), renamedProjects, childAfter, folders().get(4));
        when(storage.listFolders(userId)).thenReturn(folders(), after);
        when(storage.renameFolder(userId, projectsId, "NewProjects", null, true)).thenReturn(renamedProjects);

        var results = manager.renameMailbox(path("Проекты"), path("NewProjects"),
                MailboxManager.RenameOption.RENAME_SUBSCRIPTIONS, session);
        assertEquals(2, results.size());
        assertEquals("Проекты", results.get(0).getOriginPath().getName());
        assertEquals("NewProjects", results.get(0).getDestinationPath().getName());
        assertEquals("Проекты/2026", results.get(1).getOriginPath().getName());
        assertEquals("NewProjects/2026", results.get(1).getDestinationPath().getName());

        var renamedSent = folder(sentId, null, "SentArchive", "SENT", 1, 12, 1, 0, 0);
        List<ImapStorageClient.FolderInfo> sentAfter = List.of(
                folders().get(0), renamedSent, folders().get(2), folders().get(3), folders().get(4));
        when(storage.listFolders(userId)).thenReturn(folders(), folders(), sentAfter);
        when(storage.renameFolder(userId, sentId, "SentArchive", null, true)).thenReturn(renamedSent);
        var byId = manager.renameMailbox(CorvexMailboxId.of(sentId), path("SentArchive"),
                MailboxManager.RenameOption.NONE, session);
        assertEquals(1, byId.size());
        assertEquals(CorvexMailboxId.of(sentId), byId.get(0).getMailboxId());
    }

    @Test
    void renameMailboxCreatesMissingParentAndRejectsExistingEmptyOrFailedTargets() throws Exception {
        var oldRoot = folder(archiveId, null, "Old", null, 1, 15, 0, 0, 0);
        List<ImapStorageClient.FolderInfo> before = List.of(folders().get(0), oldRoot);
        UUID parentId = UUID.randomUUID();
        var parent = folder(parentId, null, "NewParent", null, 1, 99, 0, 0, 0);
        var renamed = folder(archiveId, parentId, "NewLeaf", null, 1, 15, 0, 0, 0);
        List<ImapStorageClient.FolderInfo> after = List.of(folders().get(0), parent, renamed);
        when(storage.listFolders(userId)).thenReturn(before, before, List.of(folders().get(0), oldRoot, parent), after);
        when(storage.createFolder(userId, "NewParent", null)).thenReturn(parent);
        when(storage.renameFolder(userId, archiveId, "NewLeaf", parentId, false)).thenReturn(renamed);

        var result = manager.renameMailbox(path("Old"), path("NewParent/NewLeaf"),
                MailboxManager.RenameOption.NONE, session);
        assertEquals("NewParent/NewLeaf", result.get(0).getDestinationPath().getName());
        verify(storage).createFolder(userId, "NewParent", null);
        verify(storage).renameFolder(userId, archiveId, "NewLeaf", parentId, false);

        when(storage.listFolders(userId)).thenReturn(folders());
        assertThrows(MailboxExistsException.class,
                () -> manager.renameMailbox(path("Archive"), path("Sent"),
                        MailboxManager.RenameOption.NONE, session));
        MailboxException empty = assertThrows(MailboxException.class,
                () -> manager.renameMailbox(path("Archive"), path(""),
                        MailboxManager.RenameOption.NONE, session));
        assertEquals(MailboxException.class, empty.getClass());

        when(storage.renameFolder(userId, archiveId, "2027", projectsId, false))
                .thenThrow(new IOException("rename down"));
        assertThrows(MailboxException.class,
                () -> manager.renameMailbox(path("Archive"), path("Проекты/2027"),
                        MailboxManager.RenameOption.NONE, session));
    }

    // --------------------------------------------------------------- copy / move

    @Test
    void copyAndMoveByIdsAndPathsMapRangesAndSkipEmptySelections() throws Exception {
        var source = List.of(meta(1, "One"), meta(2, "Two"), meta(3, "Three"));
        when(storage.listMessages(userId, inboxId, 0, 1000)).thenReturn(source);
        when(storage.listMessages(userId, sentId, 0, 1000)).thenReturn(List.of(meta(10, "One"), meta(12, "Two")));
        when(storage.copyMessages(userId, inboxId, List.of(1L, 2L), sentId, false))
                .thenReturn(new ImapStorageClient.CopyResponse(List.of(1L, 2L), List.of(10L, 12L)));

        List<MessageRange> copied = manager.copyMessages(
                MessageRange.range(MessageUid.of(1), MessageUid.of(2)),
                CorvexMailboxId.of(inboxId), CorvexMailboxId.of(sentId), session);
        assertEquals(2, copied.size());
        assertEquals(10, copied.get(0).getUidFrom().asLong());
        assertEquals(12, copied.get(1).getUidFrom().asLong());

        when(storage.listMessages(userId, sentId, 0, 1000)).thenReturn(List.of(meta(20, "Three")));
        when(storage.copyMessages(userId, inboxId, List.of(3L), sentId, true))
                .thenReturn(new ImapStorageClient.CopyResponse(List.of(3L), List.of(20L)));
        List<MessageRange> moved = manager.moveMessages(
                MessageRange.one(MessageUid.of(3)), path("INBOX"), path("Sent"), session);
        assertEquals(List.of(20L), moved.stream().map(r -> r.getUidFrom().asLong()).toList());
        verify(storage).copyMessages(userId, inboxId, List.of(3L), sentId, true);

        assertTrue(manager.copyMessages(MessageRange.one(MessageUid.of(99)),
                path("INBOX"), path("Sent"), session).isEmpty());
        verify(storage, times(2)).copyMessages(any(), any(), any(), any(), anyBoolean());

        when(storage.copyMessages(userId, inboxId, List.of(1L), sentId, true))
                .thenThrow(new IOException("move down"));
        assertThrows(MailboxException.class, () -> manager.moveMessages(
                MessageRange.one(MessageUid.of(1)), CorvexMailboxId.of(inboxId),
                CorvexMailboxId.of(sentId), session));
    }

    // ------------------------------------------------------------------- search

    @Test
    void mailboxSearchFiltersAndUsesMinimalCountersWhenRequested() throws Exception {
        MailboxQuery query = mock(MailboxQuery.class);
        when(query.matches(any(Mailbox.class))).thenAnswer(inv -> {
            Mailbox mailbox = inv.getArgument(0);
            return mailbox.generateAssociatedPath().getName().startsWith("Проекты");
        });

        List<MailboxMetaData> results = manager.search(query,
                MailboxManager.MailboxSearchFetchType.Minimal, session).collectList().block();
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(m -> m.getPath().getName().startsWith("Проекты")));
        assertTrue(results.stream().allMatch(m -> m.getCounters().getCount() == 0
                && m.getCounters().getUnseen() == 0));

        when(storage.listFolders(userId)).thenThrow(new IOException("folders down"));
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> manager.search(query, MailboxManager.MailboxSearchFetchType.Counters, session)
                        .collectList().block());
        assertTrue(error.getCause() instanceof MailboxException);
    }

    @Test
    void multimailboxSearchHonorsInNotInLimitAndStorageFailures() throws Exception {
        var inboxHit = meta(1, "Нужный отчёт");
        var inboxMiss = meta(2, "Прочее");
        var sentHit = meta(3, "Нужный в Sent");
        var projectsHit = meta(4, "Нужный проект");
        when(storage.listMessages(userId, inboxId, 0, 1000)).thenReturn(List.of(inboxHit, inboxMiss));
        when(storage.listMessages(userId, sentId, 0, 1000)).thenReturn(List.of(sentHit));
        when(storage.listMessages(userId, projectsId, 0, 1000)).thenReturn(List.of(projectsHit));
        when(storage.listMessages(userId, childId, 0, 1000)).thenReturn(List.of());
        when(storage.listMessages(userId, archiveId, 0, 1000)).thenReturn(List.of());

        MultimailboxesSearchQuery onlyInbox = MultimailboxesSearchQuery
                .from(SearchQuery.of(SearchQuery.subject("нужн")))
                .inMailboxes(CorvexMailboxId.of(inboxId), CorvexMailboxId.of(sentId))
                .notInMailboxes(CorvexMailboxId.of(sentId))
                .build();
        assertEquals(List.of(CorvexMessageId.of(inboxHit.id())),
                Flux.from(manager.search(onlyInbox, session, 0)).collectList().block());

        MultimailboxesSearchQuery all = MultimailboxesSearchQuery
                .from(SearchQuery.of(SearchQuery.subject("нужн"))).build();
        assertEquals(List.of(CorvexMessageId.of(inboxHit.id())),
                Flux.from(manager.search(all, session, 1)).collectList().block());

        when(storage.listMessages(userId, sentId, 0, 1000)).thenThrow(new IOException("sent down"));
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> Flux.from(manager.search(all, session, 0)).collectList().block());
        assertTrue(error.getCause() instanceof MailboxException);
    }

    @Test
    void threadExistenceListAndChildrenUseFolderSnapshot() throws Exception {
        UUID messageUuid = UUID.randomUUID();
        assertEquals(CorvexMessageId.of(messageUuid), Mono.from(manager.getThread(
                ThreadId.fromBaseMessageId(CorvexMessageId.of(messageUuid)), session)).block());

        assertTrue(Mono.from(manager.mailboxExists(path("inbox"), session)).block());
        assertFalse(Mono.from(manager.mailboxExists(path("Missing"), session)).block());
        assertFalse(Mono.from(manager.mailboxExists(MailboxPath.forUser(OTHER, "INBOX"), session)).block());

        List<String> names = manager.list(session).stream().map(MailboxPath::getName).toList();
        assertTrue(names.containsAll(List.of("INBOX", "Sent", "Проекты/2026", "Archive")));
        assertTrue(manager.hasChildren(path("Проекты"), session));
        assertFalse(manager.hasChildren(path("Sent"), session));
        assertTrue(Mono.from(manager.hasChildrenReactive(path("Проекты"), session)).block());
        assertThrows(MailboxNotFoundException.class, () -> manager.hasChildren(path("Missing"), session));
    }

    // -------------------------------------------------------------- rights / acl

    @Test
    void ownerRightsResolveFolderAndMutationsRemainExplicitlyUnsupported() throws Exception {
        Mailbox mailbox = manager.toMailbox(session, folders().get(0), folders());
        MailboxPath inbox = path("INBOX");
        MailboxId inboxMailboxId = CorvexMailboxId.of(inboxId);

        assertTrue(manager.hasRight(inbox, MailboxACL.Right.Read, session));
        assertTrue(manager.hasRight(mailbox, MailboxACL.Right.Write, session));
        assertTrue(manager.hasRight(inboxMailboxId, MailboxACL.Right.DeleteMessages, session));
        assertTrue(Mono.from(manager.hasRightReactive(inbox, MailboxACL.Right.Lookup, session)).block());
        assertEquals(List.of(MailboxACL.FULL_RIGHTS), manager.listRights(inbox, null, session));
        assertEquals(List.of(MailboxACL.FULL_RIGHTS), manager.listRights(mailbox, null, session));
        assertSame(MailboxACL.OWNER_FULL_ACL, manager.listRights(inbox, session));
        assertSame(MailboxACL.OWNER_FULL_ACL, manager.listRights(inboxMailboxId, session));
        assertSame(MailboxACL.FULL_RIGHTS, manager.myRights(inbox, session));
        assertSame(MailboxACL.FULL_RIGHTS, Mono.from(manager.myRightsReactive(inbox, session)).block());
        assertSame(MailboxACL.FULL_RIGHTS, Mono.from(manager.myRights(inboxMailboxId, session)).block());
        assertSame(MailboxACL.FULL_RIGHTS, manager.myRights(mailbox, session));
        assertThrows(MailboxNotFoundException.class,
                () -> manager.hasRight(path("Missing"), MailboxACL.Right.Read, session));

        assertThrows(UnsupportedRightException.class,
                () -> manager.applyRightsCommand(inbox, null, session));
        assertThrows(UnsupportedRightException.class,
                () -> manager.applyRightsCommand(inboxMailboxId, null, session));
        RuntimeException reactive = assertThrows(RuntimeException.class,
                () -> Mono.from(manager.applyRightsCommandReactive(inbox, null, session)).block());
        assertTrue(reactive.getCause() instanceof UnsupportedRightException);
        assertThrows(UnsupportedRightException.class,
                () -> manager.setRights(inbox, MailboxACL.OWNER_FULL_ACL, session));
        assertThrows(UnsupportedRightException.class,
                () -> manager.setRights(inboxMailboxId, MailboxACL.OWNER_FULL_ACL, session));
    }

    @Test
    void annotationsAreEmptyForReadsAndExplicitlyUnsupportedForWrites() {
        MailboxPath inbox = path("INBOX");
        assertTrue(manager.getAllAnnotations(inbox, session).isEmpty());
        assertTrue(Flux.from(manager.getAllAnnotationsReactive(inbox, session)).collectList().block().isEmpty());
        assertTrue(manager.getAnnotationsByKeys(inbox, session, Set.of()).isEmpty());
        assertTrue(Flux.from(manager.getAnnotationsByKeysReactive(inbox, session, Set.of()))
                .collectList().block().isEmpty());
        assertTrue(manager.getAnnotationsByKeysWithOneDepth(inbox, session, Set.of()).isEmpty());
        assertTrue(Flux.from(manager.getAnnotationsByKeysWithOneDepthReactive(inbox, session, Set.of()))
                .collectList().block().isEmpty());
        assertTrue(manager.getAnnotationsByKeysWithAllDepth(inbox, session, Set.of()).isEmpty());
        assertTrue(Flux.from(manager.getAnnotationsByKeysWithAllDepthReactive(inbox, session, Set.of()))
                .collectList().block().isEmpty());

        assertThrows(AnnotationException.class,
                () -> manager.updateAnnotations(inbox, session, List.of()));
        RuntimeException reactive = assertThrows(RuntimeException.class,
                () -> Mono.from(manager.updateAnnotationsReactive(inbox, session, List.of())).block());
        assertTrue(reactive.getCause() instanceof AnnotationException);
    }
}
