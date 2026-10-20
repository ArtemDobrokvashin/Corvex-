package ru.corvex.mailimap.server;

import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.SubscriptionManager;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.exception.SubscriptionException;
import org.apache.james.mailbox.model.MailboxPath;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Подписки IMAP (LSUB/SUBSCRIBE). Персистентности подписок в протокольном API хранилища пока нет,
 * поэтому набор живёт в памяти процесса и при первом обращении инициализируется «все папки» —
 * поведение, которого ожидают Thunderbird/Outlook после первого входа. Ограничение задокументировано:
 * после рестарта сервиса подписки снова «все папки». Перенос в хранилище — отдельная миграция этапа 5.
 */
final class CorvexSubscriptionManager implements SubscriptionManager {

    private final CorvexMailboxManager mailboxes;
    private final ConcurrentMap<UUID, Set<MailboxPath>> subscriptionsByUser = new ConcurrentHashMap<>();

    CorvexSubscriptionManager(CorvexMailboxManager mailboxes) {
        this.mailboxes = mailboxes;
    }

    private Set<MailboxPath> subscriptionsFor(MailboxSession session) throws MailboxException {
        UUID userId = mailboxes.userId(session);
        Set<MailboxPath> existing = subscriptionsByUser.get(userId);
        if (existing != null) {
            return existing;
        }
        Set<MailboxPath> defaults = new CopyOnWriteArraySet<>(mailboxes.list(session));
        Set<MailboxPath> raced = subscriptionsByUser.putIfAbsent(userId, defaults);
        return raced == null ? defaults : raced;
    }

    @Override
    public void subscribe(MailboxSession session, MailboxPath mailbox) throws SubscriptionException {
        try {
            subscriptionsFor(session).add(mailbox);
        } catch (MailboxException e) {
            throw new SubscriptionException(e);
        }
    }

    @Override
    public Publisher<Void> subscribeReactive(MailboxPath mailbox, MailboxSession session) {
        return Mono.fromRunnable(() -> {
            try {
                subscribe(session, mailbox);
            } catch (SubscriptionException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Override
    public Collection<MailboxPath> subscriptions(MailboxSession session) throws SubscriptionException {
        try {
            return List.copyOf(subscriptionsFor(session));
        } catch (MailboxException e) {
            throw new SubscriptionException(e);
        }
    }

    @Override
    public Publisher<MailboxPath> subscriptionsReactive(MailboxSession session) throws SubscriptionException {
        return Flux.fromIterable(subscriptions(session));
    }

    @Override
    public void unsubscribe(MailboxSession session, MailboxPath mailbox) throws SubscriptionException {
        try {
            subscriptionsFor(session).remove(mailbox);
        } catch (MailboxException e) {
            throw new SubscriptionException(e);
        }
    }

    @Override
    public Publisher<Void> unsubscribeReactive(MailboxPath mailbox, MailboxSession session) {
        return Mono.fromRunnable(() -> {
            try {
                unsubscribe(session, mailbox);
            } catch (SubscriptionException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Override
    public void startProcessingRequest(MailboxSession session) {
        // Stateless: подписки — in-memory набор.
    }

    @Override
    public void endProcessingRequest(MailboxSession session) {
        // Stateless: подписки — in-memory набор.
    }
}
