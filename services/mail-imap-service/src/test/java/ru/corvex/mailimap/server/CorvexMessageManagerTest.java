package ru.corvex.mailimap.server;

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.ModSeq;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.Content;
import org.apache.james.mailbox.model.FetchGroup;
import org.apache.james.mailbox.model.MailboxACL;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.MessageResultIterator;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.mailbox.model.ThreadId;
import org.apache.james.mailbox.model.UidValidity;
import org.apache.james.mailbox.model.UpdatedFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.corvex.mailimap.client.ImapStorageClient;

import javax.mail.Flags;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Прямые ветки одной папки поверх mocked manager/storage, без повторного полного SPI-слоя. */
class CorvexMessageManagerTest {

    private static final Username USER = Username.of("ivanov@corp.local");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID FOLDER_ID = UUID.randomUUID();
    private static final MailboxPath PATH = MailboxPath.inbox(USER);
    private static final Instant DATE = Instant.parse("2026-07-14T07:30:00Z");

    private CorvexMailboxManager manager;
    private ImapStorageClient storage;
    private MailboxSession session;
    private CorvexMessageManager messages;

    @BeforeEach
    void setUp() throws Exception {
        manager = mock(CorvexMailboxManager.class);
        storage = mock(ImapStorageClient.class);
        session = mock(MailboxSession.class);
        when(manager.storage()).thenReturn(storage);
        when(manager.userId(session)).thenReturn(USER_ID);
        var folder = new ImapStorageClient.FolderInfo(
                FOLDER_ID, null, "INBOX", "INBOX", 0, 0, 0, 0, 0);
        messages = new CorvexMessageManager(manager, folder, PATH);
    }

    private static ImapStorageClient.MessageMeta meta(long uid, long modseq, boolean seen,
                                                      boolean answered, boolean flagged,
                                                      boolean draft, boolean deleted) {
        return new ImapStorageClient.MessageMeta(UUID.randomUUID(), uid, modseq, "<m" + uid + "@corp.local>",
                "petrov@corp.local", "Тема " + uid, DATE, 100 + uid,
                seen, answered, flagged, draft, deleted);
    }

    private static ImapStorageClient.FolderInfo status(long uidNext, long uidValidity, long highest,
                                                       long count, long unseen) {
        return new ImapStorageClient.FolderInfo(
                FOLDER_ID, null, "INBOX", "INBOX", uidNext, uidValidity, highest, count, unseen);
    }

    @Test
    void countersAndBasicMailboxPropertiesComeFromFreshStatus() throws Exception {
        when(manager.folderStatus(session, FOLDER_ID)).thenReturn(status(9, 12, 8, 5, 2));

        assertEquals(5, messages.getMessageCount(session));
        var counters = messages.getMailboxCounters(session);
        assertEquals(CorvexMailboxId.of(FOLDER_ID), counters.getMailboxId());
        assertEquals(5, counters.getCount());
        assertEquals(2, counters.getUnseen());
        assertEquals(counters, Mono.from(messages.getMailboxCountersReactive(session)).block());

        assertTrue(messages.isWriteable(session));
        assertEquals(CorvexMailboxId.of(FOLDER_ID), messages.getId());
        assertSame(PATH, messages.getMailboxPath());
        assertEquals(EnumSet.of(MailboxManager.MessageCapabilities.UniqueID),
                messages.getSupportedMessageCapabilities());
        assertTrue(messages.getApplicableFlags(session).contains(Flags.Flag.RECENT));
        assertFalse(messages.getPermanentFlags(session).contains(Flags.Flag.RECENT));
        assertSame(MailboxACL.OWNER_FULL_ACL, messages.getResolvedAcl(session));

        var entity = messages.getMailboxEntity();
        assertEquals(CorvexMailboxId.of(FOLDER_ID), entity.getMailboxId());
        assertEquals(PATH, entity.generateAssociatedPath());
        assertEquals(UidValidity.of(1), entity.getUidValidity(),
                "uidValidity 0 из снимка папки нормализуется к 1");
        assertSame(MailboxACL.OWNER_FULL_ACL, entity.getACL());
    }

    @Test
    void searchUsesRawLoaderOnlyForRawCriteriaAndRejectsUnsupportedCriteriaSynchronously() throws Exception {
        var first = meta(1, 1, false, false, false, false, false);
        var second = meta(2, 2, true, false, false, false, false);
        when(manager.listAllMessages(session, FOLDER_ID)).thenReturn(List.of(first, second));
        when(manager.readRaw(session, FOLDER_ID, first))
                .thenReturn("X-Project: Alpha\r\n\r\nBody".getBytes(StandardCharsets.UTF_8));
        when(manager.readRaw(session, FOLDER_ID, second))
                .thenReturn("X-Project: Beta\r\n\r\nBody".getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of(MessageUid.of(1)), Flux.from(messages.search(
                SearchQuery.of(SearchQuery.headerContains("X-Project", "alpha")), session))
                .collectList().block());
        assertEquals(List.of(MessageUid.of(1), MessageUid.of(2)), Flux.from(messages.search(
                SearchQuery.matchAll(), session)).collectList().block());
        verify(manager, times(1)).readRaw(session, FOLDER_ID, first);
        verify(manager, times(1)).readRaw(session, FOLDER_ID, second);

        assertThrows(MailboxException.class, () -> messages.search(
                SearchQuery.of(SearchQuery.hasAttachment()), session));
    }

    @Test
    void expungeSkipsWhenNoDeletedAndDispatchesOnlyActuallyExpunged() throws Exception {
        var alive = meta(1, 1, true, false, false, false, false);
        when(manager.listMessagesInRange(session, FOLDER_ID, MessageRange.all())).thenReturn(List.of(alive));
        assertFalse(messages.expunge(MessageRange.all(), session).hasNext());
        verify(storage, never()).expunge(any(), any(), any());
        verify(manager, never()).dispatchExpunged(any(), any(), any(), any());

        var deletedSecond = meta(2, 2, true, false, false, false, true);
        var deletedThird = meta(3, 3, true, false, false, false, true);
        when(manager.listMessagesInRange(session, FOLDER_ID, MessageRange.all()))
                .thenReturn(List.of(alive, deletedSecond, deletedThird));
        when(storage.expunge(USER_ID, FOLDER_ID, List.of(2L, 3L))).thenReturn(List.of(3L, 99L));

        List<MessageUid> result = new java.util.ArrayList<>();
        messages.expunge(MessageRange.all(), session).forEachRemaining(result::add);
        assertEquals(List.of(MessageUid.of(3), MessageUid.of(99)), result,
                "клиенту возвращаются UID именно из ответа хранилища");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImapStorageClient.MessageMeta>> dispatched = ArgumentCaptor.forClass(List.class);
        verify(manager).dispatchExpunged(eq(session), eq(PATH),
                eq(CorvexMailboxId.of(FOLDER_ID)), dispatched.capture());
        assertEquals(List.of(deletedThird), dispatched.getValue());
    }

    @Test
    void expungeAndDeleteWrapStorageIoFailures() throws Exception {
        var deleted = meta(2, 2, true, false, false, false, true);
        when(manager.listMessagesInRange(session, FOLDER_ID, MessageRange.all())).thenReturn(List.of(deleted));
        IOException backend = new IOException("storage down");
        when(storage.expunge(USER_ID, FOLDER_ID, List.of(2L))).thenThrow(backend);
        MailboxException expungeError = assertThrows(MailboxException.class,
                () -> messages.expunge(MessageRange.all(), session));
        assertSame(backend, expungeError.getCause());

        when(storage.updateFlags(USER_ID, FOLDER_ID, 2L,
                new ImapStorageClient.FlagsUpdate(null, null, null, null, Boolean.TRUE)))
                .thenThrow(backend);
        MailboxException deleteError = assertThrows(MailboxException.class,
                () -> messages.delete(List.of(MessageUid.of(2)), session));
        assertSame(backend, deleteError.getCause());
    }

    @Test
    void deleteEmptyIsNoOpAndDeleteMarksThenExpungesEachUid() throws Exception {
        messages.delete(null, session);
        messages.delete(List.of(), session);
        verify(storage, never()).updateFlags(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(storage, never()).expunge(any(), any(), any());

        when(storage.expunge(USER_ID, FOLDER_ID, List.of(1L, 2L))).thenReturn(List.of(1L, 2L));
        messages.delete(List.of(MessageUid.of(1), MessageUid.of(2)), session);

        var deletedOnly = new ImapStorageClient.FlagsUpdate(null, null, null, null, Boolean.TRUE);
        verify(storage).updateFlags(USER_ID, FOLDER_ID, 1L, deletedOnly);
        verify(storage).updateFlags(USER_ID, FOLDER_ID, 2L, deletedOnly);
        verify(storage).expunge(USER_ID, FOLDER_ID, List.of(1L, 2L));
    }

    @Test
    void setFlagsRemoveAndReplaceOnlyPersistChangedMessages() throws Exception {
        var seenFlagged = meta(1, 5, true, false, true, false, false);
        var draft = meta(2, 6, false, false, false, true, false);
        when(manager.listMessagesInRange(session, FOLDER_ID, MessageRange.all()))
                .thenReturn(List.of(seenFlagged, draft));
        when(storage.updateFlags(eq(USER_ID), eq(FOLDER_ID), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenAnswer(inv -> meta(inv.getArgument(2, Long.class), 50, false, false, false, false, false));

        Map<MessageUid, Flags> removed = messages.setFlags(
                new Flags(Flags.Flag.SEEN), MessageManager.FlagsUpdateMode.REMOVE,
                MessageRange.all(), session);
        assertFalse(removed.get(MessageUid.of(1)).contains(Flags.Flag.SEEN));
        assertTrue(removed.get(MessageUid.of(1)).contains(Flags.Flag.FLAGGED));
        assertTrue(removed.get(MessageUid.of(2)).contains(Flags.Flag.DRAFT));
        verify(storage, times(1)).updateFlags(eq(USER_ID), eq(FOLDER_ID),
                org.mockito.ArgumentMatchers.anyLong(), any());

        Flags replace = new Flags();
        replace.add(Flags.Flag.ANSWERED);
        replace.add(Flags.Flag.RECENT);
        Map<MessageUid, Flags> replaced = messages.setFlags(
                replace, MessageManager.FlagsUpdateMode.REPLACE, MessageRange.all(), session);
        assertTrue(replaced.get(MessageUid.of(1)).contains(Flags.Flag.ANSWERED));
        assertFalse(replaced.get(MessageUid.of(1)).contains(Flags.Flag.FLAGGED));
        assertFalse(replaced.get(MessageUid.of(2)).contains(Flags.Flag.DRAFT));
        assertFalse(replaced.get(MessageUid.of(2)).contains(Flags.Flag.RECENT),
                "сессионный \\Recent после REPLACE не персистится и не возвращается как хранимый");
        verify(storage, times(3)).updateFlags(eq(USER_ID), eq(FOLDER_ID),
                org.mockito.ArgumentMatchers.anyLong(), any());
        verify(manager, times(2)).dispatchFlagsUpdated(eq(session), eq(PATH),
                eq(CorvexMailboxId.of(FOLDER_ID)), any());
    }

    @Test
    void setFlagsAddUnchangedDoesNotWriteButStorageFailureIsWrapped() throws Exception {
        var alreadySeen = meta(1, 5, true, false, true, false, false);
        when(manager.listMessagesInRange(session, FOLDER_ID, MessageRange.one(MessageUid.of(1))))
                .thenReturn(List.of(alreadySeen));

        Map<MessageUid, Flags> result = messages.setFlags(
                new Flags(Flags.Flag.SEEN), MessageManager.FlagsUpdateMode.ADD,
                MessageRange.one(MessageUid.of(1)), session);
        assertTrue(result.get(MessageUid.of(1)).contains(Flags.Flag.SEEN));
        verify(storage, never()).updateFlags(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UpdatedFlags>> dispatched = ArgumentCaptor.forClass(List.class);
        verify(manager).dispatchFlagsUpdated(eq(session), eq(PATH),
                eq(CorvexMailboxId.of(FOLDER_ID)), dispatched.capture());
        assertTrue(dispatched.getValue().isEmpty());

        IOException backend = new IOException("flags down");
        when(storage.updateFlags(eq(USER_ID), eq(FOLDER_ID), eq(1L), any())).thenThrow(backend);
        MailboxException error = assertThrows(MailboxException.class, () -> messages.setFlags(
                new Flags(Flags.Flag.DELETED), MessageManager.FlagsUpdateMode.ADD,
                MessageRange.one(MessageUid.of(1)), session));
        assertSame(backend, error.getCause());
    }

    @Test
    void appendStreamAndCommandBuildComposedResultsAndAddedEvents() throws Exception {
        byte[] raw = "Subject: Append\r\n\r\nBody".getBytes(StandardCharsets.UTF_8);
        UUID createdId = UUID.randomUUID();
        when(storage.append(eq(USER_ID), eq(FOLDER_ID), any(byte[].class), any(), any()))
                .thenReturn(new ImapStorageClient.AppendResponse(createdId, 21, 22));

        var streamResult = messages.appendMessage(
                new ByteArrayInputStream(raw), null, session, true, null);
        assertEquals(CorvexMessageId.of(createdId), streamResult.getId().getMessageId());
        assertEquals(MessageUid.of(21), streamResult.getId().getUid());
        assertEquals(raw.length, streamResult.getSize().longValue());
        assertEquals(ThreadId.fromBaseMessageId(CorvexMessageId.of(createdId)), streamResult.getThreadId());

        ArgumentCaptor<byte[]> rawCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<Instant> dateCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<String> flagsCaptor = ArgumentCaptor.forClass(String.class);
        verify(storage).append(eq(USER_ID), eq(FOLDER_ID), rawCaptor.capture(), dateCaptor.capture(),
                flagsCaptor.capture());
        assertArrayEquals(raw, rawCaptor.getValue());
        assertFalse(dateCaptor.getValue().isAfter(Instant.now().plusSeconds(5)));
        assertEquals("", flagsCaptor.getValue());

        Flags flags = new Flags();
        flags.add(Flags.Flag.SEEN);
        flags.add(Flags.Flag.DRAFT);
        flags.add(Flags.Flag.RECENT);
        MessageManager.AppendCommand command = MessageManager.AppendCommand.builder()
                .withInternalDate(Date.from(DATE))
                .withFlags(flags)
                .isRecent(true)
                .delivery()
                .build(raw);
        var commandResult = Mono.from(messages.appendMessageReactive(command, session)).block();
        assertEquals(MessageUid.of(21), commandResult.getId().getUid());
        verify(storage).append(eq(USER_ID), eq(FOLDER_ID), any(byte[].class), eq(DATE), eq("\\Seen \\Draft"));
        verify(manager, times(1)).dispatchAdded(eq(session), eq(PATH),
                eq(CorvexMailboxId.of(FOLDER_ID)), any(), eq(false));
        verify(manager, times(1)).dispatchAdded(eq(session), eq(PATH),
                eq(CorvexMailboxId.of(FOLDER_ID)), any(), eq(true));
    }

    @Test
    void appendReadAndStorageFailuresBecomeMailboxExceptions() {
        IOException readFailure = new IOException("broken literal");
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw readFailure;
            }
        };
        MailboxException streamError = assertThrows(MailboxException.class,
                () -> messages.appendMessage(failing, new Date(), session, false, null));
        assertSame(readFailure, streamError.getCause());

        Content failingContent = new Content() {
            @Override
            public InputStream getInputStream() {
                return failing;
            }

            @Override
            public long size() {
                return 0;
            }
        };
        MessageManager.AppendCommand command = MessageManager.AppendCommand.builder().build(failingContent);
        MailboxException commandError = assertThrows(MailboxException.class,
                () -> messages.appendMessage(command, session));
        assertSame(readFailure, commandError.getCause());

        IOException storageFailure = new IOException("append down");
        try {
            when(storage.append(eq(USER_ID), eq(FOLDER_ID), any(byte[].class), any(), any()))
                    .thenThrow(storageFailure);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        MailboxException storageError = assertThrows(MailboxException.class,
                () -> messages.appendMessage(new ByteArrayInputStream(new byte[]{1}), new Date(), session, false, null));
        assertSame(storageFailure, storageError.getCause());
    }

    @Test
    void getMessagesReturnsSnapshotIteratorAndMetadataListHandlesFailures() throws Exception {
        var first = meta(1, 0, false, false, true, false, false);
        var second = meta(2, 3, true, true, false, false, false);
        when(manager.listMessagesInRange(session, FOLDER_ID, MessageRange.all()))
                .thenReturn(List.of(first, second));

        MessageResultIterator iterator = messages.getMessages(
                MessageRange.all(), FetchGroup.FULL_CONTENT, session);
        assertTrue(iterator.hasNext());
        assertEquals(MessageUid.of(1), iterator.next().getUid());
        assertEquals(MessageUid.of(2), iterator.next().getUid());
        assertFalse(iterator.hasNext());
        assertNull(iterator.getException());
        assertThrows(NoSuchElementException.class, iterator::next);
        assertThrows(UnsupportedOperationException.class, iterator::remove);

        var metadata = Flux.from(messages.listMessagesMetadata(MessageRange.all(), session))
                .collectList().block();
        assertEquals(2, metadata.size());
        assertEquals(CorvexMessageId.of(first.id()), metadata.get(0).getComposedMessageId().getMessageId());
        assertEquals(ModSeq.of(1), metadata.get(0).getModSeq());
        assertTrue(metadata.get(0).getFlags().contains(Flags.Flag.FLAGGED));
        assertEquals(ThreadId.fromBaseMessageId(CorvexMessageId.of(second.id())), metadata.get(1).getThreadId());

        MailboxException failure = new MailboxException("range unavailable");
        when(manager.listMessagesInRange(session, FOLDER_ID, MessageRange.all())).thenThrow(failure);
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> Flux.from(messages.listMessagesMetadata(MessageRange.all(), session)).collectList().block());
        assertSame(failure, error.getCause());
    }

    @Test
    void metaDataSupportsNullItemsSkipsFirstUnseenWhenNotRequestedAndNormalizesZeros() throws Exception {
        when(manager.folderStatus(session, FOLDER_ID)).thenReturn(status(0, 0, 0, 3, 2));
        var seen = meta(1, 1, true, false, false, false, false);
        var firstUnseen = meta(2, 2, false, false, false, false, false);
        when(manager.listAllMessages(session, FOLDER_ID)).thenReturn(List.of(seen, firstUnseen));

        var nullItems = messages.getMetaData(
                MessageManager.MailboxMetaData.RecentMode.RETRIEVE, session,
                (EnumSet<MessageManager.MailboxMetaData.Item>) null);
        assertEquals(MessageUid.of(2), nullItems.getFirstUnseen());
        assertEquals(UidValidity.of(1), nullItems.getUidValidity());
        assertEquals(MessageUid.of(1), nullItems.getUidNext());
        assertEquals(ModSeq.of(1), nullItems.getHighestModSeq());
        assertEquals(3, nullItems.getMessageCount());
        assertEquals(2, nullItems.getUnseenCount());
        assertEquals(0, nullItems.countRecent());
        assertTrue(nullItems.isWriteable());
        assertSame(MailboxACL.OWNER_FULL_ACL, nullItems.getACL());

        var noFirstUnseen = messages.getMetaData(
                MessageManager.MailboxMetaData.RecentMode.RESET, session, EnumSet.noneOf(
                        MessageManager.MailboxMetaData.Item.class));
        assertNull(noFirstUnseen.getFirstUnseen());
        verify(manager, times(1)).listAllMessages(session, FOLDER_ID);
    }
}
