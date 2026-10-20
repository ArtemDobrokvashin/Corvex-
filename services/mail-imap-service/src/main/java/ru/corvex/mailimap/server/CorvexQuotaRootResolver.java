package ru.corvex.mailimap.server;

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MailboxConstants;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.QuotaRoot;
import org.apache.james.mailbox.quota.QuotaRootResolver;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * Quota root в Corvex — всегда владелец ящика: "#private&user@example.org" (формат как у James
 * DefaultUserQuotaRootResolver, чтобы GETQUOTAROOT отвечал привычным клиентам значением).
 */
final class CorvexQuotaRootResolver implements QuotaRootResolver {

    private static final String SEPARATOR = "&";

    private final CorvexMailboxManager mailboxes;

    CorvexQuotaRootResolver(CorvexMailboxManager mailboxes) {
        this.mailboxes = mailboxes;
    }

    QuotaRoot forUser(Username username) {
        return QuotaRoot.quotaRoot(MailboxConstants.USER_NAMESPACE + SEPARATOR + username.asString(),
                username.getDomainPart());
    }

    @Override
    public QuotaRoot getQuotaRoot(MailboxPath mailboxPath) {
        return Optional.ofNullable(mailboxPath.getUser())
                .map(this::forUser)
                .orElseGet(() -> QuotaRoot.quotaRoot(mailboxPath.getNamespace(), Optional.empty()));
    }

    @Override
    public Publisher<QuotaRoot> getQuotaRootReactive(MailboxPath mailboxPath) {
        return Mono.just(getQuotaRoot(mailboxPath));
    }

    @Override
    public QuotaRoot getQuotaRoot(MailboxId mailboxId) throws MailboxException {
        throw new MailboxException("Quota root по MailboxId требует сессии владельца; используйте getQuotaRoot(MailboxPath)");
    }

    @Override
    public QuotaRoot getQuotaRoot(Mailbox mailbox) {
        return getQuotaRoot(mailbox.generateAssociatedPath());
    }

    @Override
    public Publisher<QuotaRoot> getQuotaRootReactive(Mailbox mailbox) {
        return Mono.just(getQuotaRoot(mailbox));
    }

    @Override
    public Publisher<QuotaRoot> getQuotaRootReactive(MailboxId mailboxId) {
        return Mono.error(new MailboxException(
                "Quota root по MailboxId требует сессии владельца; используйте getQuotaRoot(MailboxPath)"));
    }

    @Override
    public Publisher<Mailbox> retrieveAssociatedMailboxes(QuotaRoot quotaRoot, MailboxSession mailboxSession) {
        return Flux.defer(() -> {
            try {
                List<ru.corvex.mailimap.client.ImapStorageClient.FolderInfo> folders =
                        mailboxes.listFolders(mailboxSession);
                return Flux.fromIterable(folders.stream()
                        .map(folder -> mailboxes.toMailbox(mailboxSession, folder, folders))
                        .toList());
            } catch (MailboxException e) {
                return Flux.error(e);
            }
        });
    }

    @Override
    public Username associatedUsername(QuotaRoot quotaRoot) {
        String value = quotaRoot.getValue();
        int separator = value.indexOf(SEPARATOR);
        String username = separator >= 0 ? value.substring(separator + 1) : value;
        return Username.of(username);
    }

    @Override
    public QuotaRoot fromString(String serializedQuotaRoot) throws MailboxException {
        int separator = serializedQuotaRoot.indexOf(SEPARATOR);
        if (separator < 0) {
            return QuotaRoot.quotaRoot(serializedQuotaRoot, Optional.empty());
        }
        Username username = Username.of(serializedQuotaRoot.substring(separator + 1));
        return QuotaRoot.quotaRoot(serializedQuotaRoot, username.getDomainPart());
    }
}
