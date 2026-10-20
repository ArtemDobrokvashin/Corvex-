package ru.corvex.mailimap.server;

import org.apache.james.core.Username;
import org.apache.james.events.EventBus;
import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.exception.BadCredentialsException;
import org.apache.james.mailbox.model.MailboxMetaData;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.MessageResult;
import org.apache.james.mailbox.model.FetchGroup;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.mailbox.model.search.MailboxQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import ru.corvex.mailimap.client.ImapAuthClient;
import ru.corvex.mailimap.client.ImapStorageClient;

import javax.mail.Flags;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * James-адаптер поверх storage protocol API: маппинг сессий, папок, UID/MODSEQ, STORE/COPY/EXPUNGE,
 * SEARCH по метаданным и FETCH-представление сырого .eml. Сеть мокается — проверяем именно перевод SPI.
 */
class CorvexMailboxManagerTest {

    private static final String EMAIL = "ivanov@corp.local";

    private final UUID userId = UUID.randomUUID();
    private final UUID inboxId = UUID.randomUUID();
    private final UUID sentId = UUID.randomUUID();
    private final UUID projectsId = UUID.randomUUID();
    private final UUID y2026Id = UUID.randomUUID();

    private ImapAuthClient auth;
    private ImapStorageClient storage;
    private CorvexMailboxManager manager;
    private MailboxSession session;

    @BeforeEach
    void setUp() throws Exception {
        auth = mock(ImapAuthClient.class);
        storage = mock(ImapStorageClient.class);
        manager = new CorvexMailboxManager(auth, storage, mock(EventBus.class));
        when(auth.findActiveByUsername(anyString()))
                .thenReturn(Optional.of(new ImapAuthClient.AuthenticatedUser(userId, EMAIL, "Ivanov")));
        session = manager.createSystemSession(Username.of(EMAIL));
        when(storage.listFolders(userId)).thenReturn(folders());
    }

    private List<ImapStorageClient.FolderInfo> folders() {
        return List.of(
                new ImapStorageClient.FolderInfo(inboxId, null, "INBOX", "INBOX", 4, 11, 7, 3, 1),
                new ImapStorageClient.FolderInfo(sentId, null, "Sent", "SENT", 1, 12, 1, 0, 0),
                new ImapStorageClient.FolderInfo(projectsId, null, "Проекты", null, 1, 13, 1, 0, 0),
                new ImapStorageClient.FolderInfo(y2026Id, projectsId, "2026", null, 3, 14, 2, 2, 2));
    }

    private ImapStorageClient.MessageMeta meta(UUID id, long uid, long modseq, String subject,
                                               boolean seen, boolean flagged, boolean deleted) {
        return new ImapStorageClient.MessageMeta(id, uid, modseq, "<m" + uid + "@x>", "ivanov@corp.local",
                subject, Instant.parse("2026-10-20T10:15:00Z"), 100 + uid, seen, false, flagged, false, deleted);
    }

    private List<ImapStorageClient.MessageMeta> inboxMetas() {
        return List.of(
                meta(UUID.randomUUID(), 1, 5, "Отчёт за октябрь", false, true, false),
                meta(UUID.randomUUID(), 2, 6, "Встреча", true, false, false),
                meta(UUID.randomUUID(), 3, 7, "Старое", false, false, true));
    }

    @Test
    void authenticateBindsStorageUserIdAndRejectsBadPassword() throws Exception {
        when(auth.authenticate(EMAIL, "secret"))
                .thenReturn(Optional.of(new ImapAuthClient.AuthenticatedUser(userId, EMAIL, "Ivanov")));
        MailboxSession userSession = manager.authenticate(Username.of(EMAIL), "secret").withoutDelegation();
        assertEquals(userId, manager.userId(userSession));

        when(auth.authenticate(EMAIL, "wrong")).thenReturn(Optional.empty());
        assertThrows(BadCredentialsException.class,
                () -> manager.authenticate(Username.of(EMAIL), "wrong").withoutDelegation());
    }

    @Test
    void listSearchBuildsPathsWithSlashDelimiterAndCounters() {
        List<MailboxMetaData> result = manager.search(
                        MailboxQuery.privateMailboxesBuilder(session).build(),
                        MailboxManager.MailboxSearchFetchType.Counters, session)
                .collectList().block();
        assertTrue(result.stream().anyMatch(m -> m.getPath().getName().equals("Проекты/2026")));
        MailboxMetaData inbox = result.stream().filter(m -> m.getPath().getName().equals("INBOX")).findFirst().orElseThrow();
        assertEquals(3, inbox.getCounters().getCount());
        assertEquals(1, inbox.getCounters().getUnseen());
        assertEquals('/', inbox.getHierarchyDelimiter());
    }

    @Test
    void createMailboxCreatesIntermediateFolders() throws Exception {
        AtomicInteger created = new AtomicInteger();
        when(storage.createFolder(eq(userId), anyString(), any())).thenAnswer(inv -> {
            String name = inv.getArgument(1);
            UUID parentId = inv.getArgument(2);
            created.incrementAndGet();
            return new ImapStorageClient.FolderInfo(UUID.randomUUID(), parentId, name, null, 1, 99, 1, 0, 0);
        });

        var id = manager.createMailbox(MailboxPath.forUser(session.getUser(), "Архив/2025"),
                MailboxManager.CreateOption.NONE, session);
        assertTrue(id.isPresent());
        assertEquals(2, created.get(), "родитель и лист создаются отдельными папками хранилища");
    }

    @Test
    void renameMailboxMovesToAnotherParentViaStorageParentId() throws Exception {
        var renamed = new ImapStorageClient.FolderInfo(y2026Id, projectsId, "2026-archive", null, 3, 14, 2, 2, 2);
        when(storage.renameFolder(userId, y2026Id, "2026-archive", projectsId, false)).thenReturn(renamed);
        when(storage.listFolders(userId)).thenReturn(folders(), List.of(
                folders().get(0), folders().get(1), folders().get(2), renamed));

        var results = manager.renameMailbox(
                MailboxPath.forUser(session.getUser(), "Проекты/2026"),
                MailboxPath.forUser(session.getUser(), "Проекты/2026-archive"),
                MailboxManager.RenameOption.NONE, session);
        assertEquals(1, results.size());
        assertEquals("Проекты/2026-archive", results.get(0).getDestinationPath().getName());
        verify(storage).renameFolder(userId, y2026Id, "2026-archive", projectsId, false);
    }

    @Test
    void copyMessagesMapsToStorageAndReturnsTargetUidRanges() throws Exception {
        var source = inboxMetas();
        when(storage.listMessages(userId, inboxId, 0L, 1000)).thenReturn(source);
        when(storage.listMessages(userId, sentId, 0L, 1000)).thenReturn(List.of(
                meta(UUID.randomUUID(), 5, 2, "Отчёт за октябрь", false, true, false),
                meta(UUID.randomUUID(), 6, 3, "Встреча", true, false, false)));
        when(storage.copyMessages(userId, inboxId, List.of(1L, 2L), sentId, false))
                .thenReturn(new ImapStorageClient.CopyResponse(List.of(1L, 2L), List.of(5L, 6L)));

        List<MessageRange> ranges = manager.copyMessages(MessageRange.range(MessageUid.of(1), MessageUid.of(2)),
                MailboxPath.inbox(session.getUser()), MailboxPath.forUser(session.getUser(), "Sent"), session);
        assertEquals(1, ranges.size(), "соседние target UID собираются в один диапазон COPYUID");
        assertEquals(5, ranges.get(0).getUidFrom().asLong());
        assertEquals(6, ranges.get(0).getUidTo().asLong());
    }

    @Test
    void messageManagerExposesSelectMetadataSearchFlagsAndExpunge() throws Exception {
        var metas = inboxMetas();
        when(storage.listMessages(userId, inboxId, 0L, 1000)).thenReturn(metas);
        when(storage.folderStatus(userId, inboxId)).thenReturn(folders().get(0));
        MessageManager inbox = manager.getMailbox(MailboxPath.inbox(session.getUser()), session);

        var metaData = inbox.getMetaData(MessageManager.MailboxMetaData.RecentMode.IGNORE, session,
                EnumSet.allOf(MessageManager.MailboxMetaData.Item.class));
        assertEquals(11, metaData.getUidValidity().asLong());
        assertEquals(4, metaData.getUidNext().asLong());
        assertEquals(7, metaData.getHighestModSeq().asLong(), "CONDSTORE HIGHESTMODSEQ берётся из хранилища");
        assertEquals(1, metaData.getFirstUnseen().asLong());

        List<MessageUid> found = Flux.from(inbox.search(SearchQuery.of(
                        SearchQuery.flagIsUnSet(Flags.Flag.SEEN),
                        SearchQuery.subject("отчёт")), session))
                .collectList().block();
        assertEquals(List.of(MessageUid.of(1)), found);

        var updated = meta(metas.get(0).id(), 1, 8, "Отчёт за октябрь", true, true, false);
        when(storage.updateFlags(eq(userId), eq(inboxId), eq(1L), any())).thenReturn(updated);
        Flags addSeen = new Flags(Flags.Flag.SEEN);
        var flagResults = inbox.setFlags(addSeen, MessageManager.FlagsUpdateMode.ADD,
                MessageRange.one(MessageUid.of(1)), session);
        assertTrue(flagResults.get(MessageUid.of(1)).contains(Flags.Flag.SEEN));
        var updateCaptor = ArgumentCaptor.forClass(ImapStorageClient.FlagsUpdate.class);
        verify(storage).updateFlags(eq(userId), eq(inboxId), eq(1L), updateCaptor.capture());
        assertEquals(Boolean.TRUE, updateCaptor.getValue().seen());
        assertEquals(Boolean.TRUE, updateCaptor.getValue().flagged(), "полный снапшот не теряет существующие флаги");

        when(storage.expunge(userId, inboxId, List.of(3L))).thenReturn(List.of(3L));
        List<MessageUid> expunged = new java.util.ArrayList<>();
        inbox.expunge(MessageRange.all(), session).forEachRemaining(expunged::add);
        assertEquals(List.of(MessageUid.of(3)), expunged, "EXPUNGE трогает только уже помеченные \\Deleted");
    }

    @Test
    void messageResultServesFetchBodyHeadersAndMimeStructure() throws Exception {
        var metas = inboxMetas();
        when(storage.listMessages(userId, inboxId, 0L, 1000)).thenReturn(metas);
        byte[] raw = ("From: ivanov@corp.local\r\nSubject: Отчёт за октябрь\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\nТело письма")
                .getBytes(StandardCharsets.UTF_8);
        when(storage.readRawByUid(userId, inboxId, 1L)).thenReturn(raw);
        MessageManager inbox = manager.getMailbox(MailboxPath.inbox(session.getUser()), session);

        var iterator = inbox.getMessages(MessageRange.one(MessageUid.of(1)), FetchGroup.FULL_CONTENT, session);
        assertTrue(iterator.hasNext());
        MessageResult result = iterator.next();
        assertEquals(metas.get(0).id().toString(), result.getMessageId().serialize());
        assertEquals("text", result.getMimeDescriptor().getMimeType());
        assertTrue(new String(result.getBody().getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .contains("Тело письма"));
        assertTrue(result.getHeaders().headers().hasNext());
    }
}
