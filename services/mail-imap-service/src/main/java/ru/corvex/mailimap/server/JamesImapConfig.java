package ru.corvex.mailimap.server;

import org.apache.james.events.EventBus;
import org.apache.james.events.InVMEventBus;
import org.apache.james.events.MemoryEventDeadLetters;
import org.apache.james.events.RetryBackoffConfiguration;
import org.apache.james.events.delivery.InVmEventDelivery;
import org.apache.james.imap.api.process.ImapProcessor;
import org.apache.james.imap.processor.main.DefaultImapProcessorFactory;
import org.apache.james.mailbox.SubscriptionManager;
import org.apache.james.mailbox.quota.QuotaManager;
import org.apache.james.mailbox.quota.QuotaRootResolver;
import org.apache.james.metrics.api.MetricFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.corvex.mailimap.client.ImapAuthClient;
import ru.corvex.mailimap.client.ImapStorageClient;

/**
 * Ручная сборка стека Apache James IMAP без Guice-контейнера Джеймса: Corvex остаётся на Spring,
 * а Джеймсу передаются только готовые SPI-объекты (MailboxManager и компания). IDLE/NOTIFY живут
 * на InVM EventBus внутри этого процесса: изменения из других процессов клиент узнаёт по NOOP/переSELECT.
 */
@Configuration
public class JamesImapConfig {

    @Bean
    public MetricFactory jamesMetricFactory() {
        return NoopMetricFactory.INSTANCE;
    }

    @Bean
    public EventBus jamesEventBus(MetricFactory metricFactory) {
        return new InVMEventBus(new InVmEventDelivery(metricFactory),
                RetryBackoffConfiguration.DEFAULT, new MemoryEventDeadLetters());
    }

    @Bean
    public CorvexMailboxManager corvexMailboxManager(ImapAuthClient auth, ImapStorageClient storage, EventBus eventBus) {
        return new CorvexMailboxManager(auth, storage, eventBus);
    }

    @Bean
    public SubscriptionManager corvexSubscriptionManager(CorvexMailboxManager mailboxes) {
        return new CorvexSubscriptionManager(mailboxes);
    }

    @Bean
    public CorvexQuotaRootResolver corvexQuotaRootResolver(CorvexMailboxManager mailboxes) {
        return new CorvexQuotaRootResolver(mailboxes);
    }

    @Bean
    public QuotaManager corvexQuotaManager(CorvexMailboxManager mailboxes, CorvexQuotaRootResolver quotaRoots) {
        return new CorvexQuotaManager(mailboxes, quotaRoots);
    }

    @Bean
    public ImapProcessor jamesImapProcessor(CorvexMailboxManager mailboxes, EventBus eventBus,
                                            SubscriptionManager subscriptions, QuotaManager quotaManager,
                                            QuotaRootResolver quotaRoots, MetricFactory metricFactory) {
        return DefaultImapProcessorFactory.createDefaultProcessor(
                mailboxes, eventBus, subscriptions, quotaManager, quotaRoots, metricFactory);
    }
}
