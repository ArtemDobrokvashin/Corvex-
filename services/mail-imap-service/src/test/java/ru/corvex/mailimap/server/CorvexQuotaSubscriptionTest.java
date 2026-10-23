package ru.corvex.mailimap.server;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.exception.SubscriptionException;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MailboxConstants;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.QuotaRoot;
import org.apache.james.mailbox.model.UidValidity;
import org.apache.james.mailbox.quota.QuotaManager;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.corvex.mailimap.client.ImapStorageClient;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** LSUB/SUBSCRIBE, QuotaRoot и RFC 9208 quota вокруг снимков mail-storage. */
class CorvexQuotaSubscriptionTest {

    private static final Username USER = Username.of("ivanov@corp.local");
    private static final Username OTHER = Username.of("petrov@corp.local");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();

    @Test
    void subscriptionsDefaultToAllFoldersPerUserAndThenTrackChanges() throws Exception {
        CorvexMailboxManager mailboxes = mock(CorvexMailboxManager.class);
        MailboxSession session = mock(MailboxSession.class);
        MailboxSession anotherSessionSameUser = mock(MailboxSession.class);
        MailboxSession otherSession = mock(MailboxSession.class);
        MailboxPath inbox = MailboxPath.inbox(USER);
        MailboxPath archive = MailboxPath.forUser(USER, "INBOX/Archive");
        MailboxPath otherInbox = MailboxPath.inbox(OTHER);
        when(mailboxes.userId(session)).thenReturn(USER_ID);
        when(mailboxes.userId(anotherSessionSameUser)).thenReturn(USER_ID);
        when(mailboxes.userId(otherSession)).thenReturn(OTHER_ID);
        when(mailboxes.list(session)).thenReturn(List.of(inbox));
        when(mailboxes.list(otherSession)).thenReturn(List.of(otherInbox));
        CorvexSubscriptionManager subscriptions = new CorvexSubscriptionManager(mailboxes);

        assertEquals(List.of(inbox), List.copyOf(subscriptions.subscriptions(session)));
        subscriptions.subscribe(session, archive);
        assertEquals(List.of(inbox, archive),
                Flux.from(subscriptions.subscriptionsReactive(anotherSessionSameUser)).collectList().block());
        subscriptions.unsubscribe(session, inbox);
        assertEquals(List.of(archive), List.copyOf(subscriptions.subscriptions(session)));

        Mono.from(subscriptions.subscribeReactive(inbox, session)).block();
        Mono.from(subscriptions.unsubscribeReactive(archive, session)).block();
        assertEquals(List.of(inbox), List.copyOf(subscriptions.subscriptions(session)));
        verify(mailboxes, times(1)).list(session);
        verify(mailboxes, times(0)).list(anotherSessionSameUser);

        assertEquals(List.of(otherInbox), List.copyOf(subscriptions.subscriptions(otherSession)),
                "набор подписок изолирован по владельцу");
        subscriptions.startProcessingRequest(session);
        subscriptions.endProcessingRequest(session);
    }

    @Test
    void subscriptionErrorsAreWrappedForSyncAndReactiveCallers() throws Exception {
        CorvexMailboxManager mailboxes = mock(CorvexMailboxManager.class);
        MailboxSession session = mock(MailboxSession.class);
        MailboxException failure = new MailboxException("storage unavailable");
        when(mailboxes.userId(session)).thenThrow(failure);
        CorvexSubscriptionManager subscriptions = new CorvexSubscriptionManager(mailboxes);
        MailboxPath inbox = MailboxPath.inbox(USER);

        SubscriptionException subscribeError = assertThrows(SubscriptionException.class,
                () -> subscriptions.subscribe(session, inbox));
        assertSame(failure, subscribeError.getCause());
        assertThrows(SubscriptionException.class, () -> subscriptions.subscriptions(session));
        assertThrows(SubscriptionException.class, () -> subscriptions.subscriptionsReactive(session));
        assertThrows(SubscriptionException.class, () -> subscriptions.unsubscribe(session, inbox));

        IllegalStateException reactiveSubscribe = assertThrows(IllegalStateException.class,
                () -> Mono.from(subscriptions.subscribeReactive(inbox, session)).block());
        assertTrue(reactiveSubscribe.getCause() instanceof SubscriptionException);
        IllegalStateException reactiveUnsubscribe = assertThrows(IllegalStateException.class,
                () -> Mono.from(subscriptions.unsubscribeReactive(inbox, session)).block());
        assertTrue(reactiveUnsubscribe.getCause() instanceof SubscriptionException);
    }

    @Test
    void quotaRootResolverCoversUserSharedMailboxAndSerializedForms() throws Exception {
        CorvexQuotaRootResolver resolver = new CorvexQuotaRootResolver(mock(CorvexMailboxManager.class));

        QuotaRoot forUser = resolver.forUser(USER);
        assertEquals(MailboxConstants.USER_NAMESPACE + "&ivanov@corp.local", forUser.getValue());
        MailboxPath inbox = MailboxPath.inbox(USER);
        assertEquals(forUser, resolver.getQuotaRoot(inbox));
        assertEquals(forUser, Mono.from(resolver.getQuotaRootReactive(inbox)).block());

        MailboxPath shared = new MailboxPath("#shared", null, "Team");
        QuotaRoot sharedRoot = resolver.getQuotaRoot(shared);
        assertEquals("#shared", sharedRoot.getValue());

        Mailbox mailbox = new Mailbox(inbox, UidValidity.of(77), CorvexMailboxId.of(UUID.randomUUID()));
        assertEquals(forUser, resolver.getQuotaRoot(mailbox));
        assertEquals(forUser, Mono.from(resolver.getQuotaRootReactive(mailbox)).block());

        CorvexMailboxId mailboxId = CorvexMailboxId.of(UUID.randomUUID());
        assertThrows(MailboxException.class, () -> resolver.getQuotaRoot(mailboxId));
        RuntimeException reactive = assertThrows(RuntimeException.class,
                () -> Mono.from(resolver.getQuotaRootReactive(mailboxId)).block());
        assertTrue(reactive.getCause() instanceof MailboxException);

        assertEquals(USER, resolver.associatedUsername(forUser));
        assertEquals(USER, resolver.associatedUsername(
                QuotaRoot.quotaRoot("ivanov@corp.local", Optional.of(Domain.of("corp.local")))));
        assertEquals(forUser, resolver.fromString("#private&ivanov@corp.local"));
        assertEquals("#shared", resolver.fromString("#shared").getValue());
    }

    @Test
    void quotaRootResolverListsAssociatedMailboxesAndPropagatesFailures() throws Exception {
        CorvexMailboxManager mailboxes = mock(CorvexMailboxManager.class);
        MailboxSession session = mock(MailboxSession.class);
        UUID inboxId = UUID.randomUUID();
        UUID archiveId = UUID.randomUUID();
        var inboxFolder = folder(inboxId, null, "INBOX");
        var archiveFolder = folder(archiveId, inboxId, "Archive");
        List<ImapStorageClient.FolderInfo> folders = List.of(inboxFolder, archiveFolder);
        Mailbox inbox = new Mailbox(MailboxPath.inbox(USER), UidValidity.of(1), CorvexMailboxId.of(inboxId));
        Mailbox archive = new Mailbox(MailboxPath.forUser(USER, "INBOX/Archive"),
                UidValidity.of(2), CorvexMailboxId.of(archiveId));
        when(mailboxes.listFolders(session)).thenReturn(folders);
        when(mailboxes.toMailbox(session, inboxFolder, folders)).thenReturn(inbox);
        when(mailboxes.toMailbox(session, archiveFolder, folders)).thenReturn(archive);
        CorvexQuotaRootResolver resolver = new CorvexQuotaRootResolver(mailboxes);
        QuotaRoot root = resolver.forUser(USER);

        assertEquals(List.of(inbox, archive),
                Flux.from(resolver.retrieveAssociatedMailboxes(root, session)).collectList().block());

        MailboxException failure = new MailboxException("folders unavailable");
        when(mailboxes.listFolders(session)).thenThrow(failure);
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> Flux.from(resolver.retrieveAssociatedMailboxes(root, session)).collectList().block());
        assertSame(failure, error.getCause());
    }

    @Test
    void quotaManagerMapsStorageUsageLimitedAndUnlimitedViews() throws Exception {
        CorvexMailboxManager mailboxes = mock(CorvexMailboxManager.class);
        ImapStorageClient storage = mock(ImapStorageClient.class);
        MailboxSession systemSession = mock(MailboxSession.class);
        when(mailboxes.createSystemSession(USER)).thenReturn(systemSession);
        when(mailboxes.userId(systemSession)).thenReturn(USER_ID);
        when(mailboxes.storage()).thenReturn(storage);
        when(storage.quota(USER_ID)).thenReturn(new ImapStorageClient.QuotaView(2048, 4096, 7));
        CorvexQuotaRootResolver roots = new CorvexQuotaRootResolver(mailboxes);
        CorvexQuotaManager quotaManager = new CorvexQuotaManager(mailboxes, roots);
        QuotaRoot root = roots.forUser(USER);

        var messageQuota = quotaManager.getMessageQuota(root);
        assertEquals(7, messageQuota.getUsed().asLong());
        assertTrue(messageQuota.getLimit().isUnlimited());
        assertFalse(messageQuota.isOverQuota());

        var storageQuota = quotaManager.getStorageQuota(root);
        assertEquals(2048, storageQuota.getUsed().asLong());
        assertEquals(4096, storageQuota.getLimit().asLong());
        assertEquals(0.5, storageQuota.getRatio(), 0.001);
        assertTrue(storageQuota.getLimitByScope().isEmpty());

        QuotaManager.Quotas quotas = quotaManager.getQuotas(root);
        assertEquals(7, quotas.getMessageQuota().getUsed().asLong());
        assertEquals(2048, quotas.getStorageQuota().getUsed().asLong());
        assertEquals(4096, Mono.from(quotaManager.getQuotasReactive(root)).block()
                .getStorageQuota().getLimit().asLong());

        when(storage.quota(USER_ID)).thenReturn(new ImapStorageClient.QuotaView(10, 0, 1));
        assertTrue(quotaManager.getStorageQuota(root).getLimit().isUnlimited(),
                "0 в протокольном API означает безлимитную квоту");
    }

    @Test
    void quotaManagerWrapsStorageIoAsMailboxException() throws Exception {
        CorvexMailboxManager mailboxes = mock(CorvexMailboxManager.class);
        ImapStorageClient storage = mock(ImapStorageClient.class);
        MailboxSession systemSession = mock(MailboxSession.class);
        when(mailboxes.createSystemSession(USER)).thenReturn(systemSession);
        when(mailboxes.userId(systemSession)).thenReturn(USER_ID);
        when(mailboxes.storage()).thenReturn(storage);
        IOException backend = new IOException("storage down");
        when(storage.quota(USER_ID)).thenThrow(backend);
        CorvexQuotaRootResolver roots = new CorvexQuotaRootResolver(mailboxes);
        CorvexQuotaManager quotaManager = new CorvexQuotaManager(mailboxes, roots);

        MailboxException error = assertThrows(MailboxException.class,
                () -> quotaManager.getMessageQuota(roots.forUser(USER)));
        assertSame(backend, error.getCause());
        assertThrows(MailboxException.class, () -> quotaManager.getQuotas(roots.forUser(USER)));
        verifyNoInteractions(systemSession);
    }

    private static ImapStorageClient.FolderInfo folder(UUID id, UUID parentId, String name) {
        return new ImapStorageClient.FolderInfo(id, parentId, name, null, 1, 1, 0, 0, 0);
    }
}
