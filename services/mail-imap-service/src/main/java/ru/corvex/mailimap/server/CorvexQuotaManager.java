package ru.corvex.mailimap.server;

import org.apache.james.core.Username;
import org.apache.james.core.quota.QuotaCountLimit;
import org.apache.james.core.quota.QuotaCountUsage;
import org.apache.james.core.quota.QuotaSizeLimit;
import org.apache.james.core.quota.QuotaSizeUsage;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.Quota;
import org.apache.james.mailbox.model.QuotaRoot;
import org.apache.james.mailbox.quota.QuotaManager;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import ru.corvex.mailimap.client.ImapStorageClient;

import java.io.IOException;

/**
 * IMAP QUOTA (RFC 9208/2087): usage — протокольный API хранилища, лимит — auth-service
 * (quotaBytes пользователя; 0 в хранилище означает «без лимита», как в веб-интерфейсе).
 */
final class CorvexQuotaManager implements QuotaManager {

    private final CorvexMailboxManager mailboxes;
    private final CorvexQuotaRootResolver quotaRoots;

    CorvexQuotaManager(CorvexMailboxManager mailboxes, CorvexQuotaRootResolver quotaRoots) {
        this.mailboxes = mailboxes;
        this.quotaRoots = quotaRoots;
    }

    private ImapStorageClient.QuotaView quotaView(QuotaRoot quotaRoot) throws MailboxException {
        Username username = quotaRoots.associatedUsername(quotaRoot);
        MailboxSession session = mailboxes.createSystemSession(username);
        try {
            return mailboxes.storage().quota(mailboxes.userId(session));
        } catch (IOException e) {
            throw CorvexMailboxManager.wrapStorage(e);
        }
    }

    @Override
    public Quota<QuotaCountLimit, QuotaCountUsage> getMessageQuota(QuotaRoot quotaRoot) throws MailboxException {
        ImapStorageClient.QuotaView quota = quotaView(quotaRoot);
        return Quota.<QuotaCountLimit, QuotaCountUsage>builder()
                .used(QuotaCountUsage.count(quota.messageCount()))
                .computedLimit(QuotaCountLimit.unlimited())
                .build();
    }

    @Override
    public Quota<QuotaSizeLimit, QuotaSizeUsage> getStorageQuota(QuotaRoot quotaRoot) throws MailboxException {
        ImapStorageClient.QuotaView quota = quotaView(quotaRoot);
        QuotaSizeLimit limit = quota.quotaBytes() > 0
                ? QuotaSizeLimit.size(quota.quotaBytes())
                : QuotaSizeLimit.unlimited();
        return Quota.<QuotaSizeLimit, QuotaSizeUsage>builder()
                .used(QuotaSizeUsage.size(quota.usedBytes()))
                .computedLimit(limit)
                .build();
    }

    @Override
    public Quotas getQuotas(QuotaRoot quotaRoot) throws MailboxException {
        return new Quotas(getMessageQuota(quotaRoot), getStorageQuota(quotaRoot));
    }

    @Override
    public Publisher<Quotas> getQuotasReactive(QuotaRoot quotaRoot) {
        return Mono.fromCallable(() -> getQuotas(quotaRoot));
    }
}
