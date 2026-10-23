package ru.corvex.mailimap.server;

import org.apache.james.events.EventBus;
import org.apache.james.events.InVMEventBus;
import org.apache.james.imap.api.process.ImapProcessor;
import org.apache.james.mailbox.SubscriptionManager;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.metrics.api.Metric;
import org.apache.james.metrics.api.TimeMetric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import ru.corvex.mailimap.client.ImapAuthClient;
import ru.corvex.mailimap.client.ImapStorageClient;

import javax.mail.Flags;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Малые James SPI-обвязки: IDs, конвертация флагов, no-op метрики, FileSystem и Spring-сборка. */
class JamesSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void mailboxAndMessageIdsSerializeThroughJamesFactories() {
        UUID folderUuid = UUID.randomUUID();
        CorvexMailboxId mailboxId = CorvexMailboxId.of(folderUuid);
        assertEquals(folderUuid, mailboxId.value());
        assertEquals(folderUuid.toString(), mailboxId.serialize());
        assertEquals(folderUuid.toString(), mailboxId.toString());
        assertEquals(mailboxId, CorvexMailboxId.fromSerialized(folderUuid.toString()));
        MailboxId fromFactory = new CorvexMailboxId.Factory().fromString(folderUuid.toString());
        assertEquals(mailboxId, fromFactory);
        assertThrows(NullPointerException.class, () -> CorvexMailboxId.of(null));
        assertThrows(IllegalArgumentException.class, () -> CorvexMailboxId.fromSerialized("not-a-uuid"));

        UUID messageUuid = UUID.randomUUID();
        CorvexMessageId messageId = CorvexMessageId.of(messageUuid);
        assertEquals(messageUuid.toString(), messageId.serialize());
        assertTrue(messageId.isSerializable());
        assertEquals(messageId, CorvexMessageId.fromSerialized(messageUuid.toString()));
        CorvexMessageId.Factory factory = new CorvexMessageId.Factory();
        assertEquals(messageId, factory.fromString(messageUuid.toString()));
        MessageId first = factory.generate();
        MessageId second = factory.generate();
        assertNotNull(first);
        assertNotEquals(first.serialize(), second.serialize());
        assertThrows(NullPointerException.class, () -> CorvexMessageId.of(null));
        assertThrows(IllegalArgumentException.class, () -> factory.fromString("bad"));
    }

    @Test
    void jamesFlagsConvertEverySupportedValueAndExcludeRecentFromStorageHeader() throws Exception {
        var meta = new ImapStorageClient.MessageMeta(UUID.randomUUID(), 7, 8, "<m@corp.local>",
                "ivanov@corp.local", "Тема", Instant.now(), 42, true, true, false, true, false);
        Flags fromMeta = JamesFlags.fromMeta(meta);
        assertTrue(fromMeta.contains(Flags.Flag.SEEN));
        assertTrue(fromMeta.contains(Flags.Flag.ANSWERED));
        assertFalse(fromMeta.contains(Flags.Flag.FLAGGED));
        assertTrue(fromMeta.contains(Flags.Flag.DRAFT));

        assertTrue(JamesFlags.fromStrings(null).getSystemFlags().length == 0);
        Flags parsed = JamesFlags.fromStrings(Set.of(
                "\\Seen", "\\answered", "\\FLAGGED", "\\Draft", "\\Deleted", "\\Recent", "$Forwarded"));
        assertEquals(Set.of("\\Seen", "\\Answered", "\\Flagged", "\\Draft", "\\Deleted", "\\Recent"),
                JamesFlags.toStrings(parsed));
        assertEquals("\\Seen \\Answered \\Flagged \\Draft \\Deleted", JamesFlags.toHeader(parsed),
                "порядок стабилен, а сессионный \\Recent в хранилище не пишется");
        assertEquals(Set.of(), JamesFlags.toStrings(null));
        assertEquals("", JamesFlags.toHeader(null));

        var update = JamesFlags.toUpdate(Set.of("\\Seen", "\\Deleted"));
        assertEquals(Boolean.TRUE, update.seen());
        assertEquals(Boolean.FALSE, update.answered());
        assertEquals(Boolean.TRUE, update.deleted());

        Flags applicable = JamesFlags.applicableFlags();
        assertTrue(applicable.contains(Flags.Flag.RECENT));
        Flags permanent = JamesFlags.permanentFlags();
        assertFalse(permanent.contains(Flags.Flag.RECENT));
        assertTrue(permanent.contains(Flags.Flag.SEEN));

        var constructor = JamesFlags.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }

    @Test
    void noopMetricsKeepCountsAndReturnOriginalPublishers() {
        NoopMetricFactory factory = new NoopMetricFactory();
        Metric metric = factory.generate("imap.requests");
        assertEquals(0, metric.getCount());
        metric.increment();
        metric.add(5);
        metric.remove(2);
        metric.decrement();
        assertEquals(3, metric.getCount());

        TimeMetric timer = factory.timer("imap.fetch");
        assertEquals("imap.fetch", timer.name());
        TimeMetric.ExecutionResult result = timer.stopAndPublish();
        assertFalse(result.elasped().isNegative());
        assertSame(result, result.logWhenExceedP99(Duration.ofMillis(1)));

        Publisher<String> publisher = Mono.just("ok");
        assertSame(publisher, factory.decoratePublisherWithTimerMetric("imap.fetch", publisher));
        assertSame(publisher, factory.decoratePublisherWithTimerMetricLogP99("imap.fetch", publisher));
        assertNotNull(NoopMetricFactory.INSTANCE);
    }

    @Test
    void localFileSystemReadsFilesClasspathAndRejectsMissingResources() throws Exception {
        Path file = tempDir.resolve("imap.pem");
        Files.writeString(file, "CERTIFICATE", StandardCharsets.UTF_8);

        LocalFileSystem fileSystem = LocalFileSystem.INSTANCE;
        assertEquals(Path.of("/").toFile().getCanonicalFile(), fileSystem.getBasedir().getCanonicalFile());
        assertEquals(file.toFile().getCanonicalFile(), fileSystem.getFile(file.toString()).getCanonicalFile());
        assertEquals(file.toFile().getCanonicalFile(),
                fileSystem.getFile(LocalFileSystem.FILE_PROTOCOL + file).getCanonicalFile());
        try (InputStream in = fileSystem.getResource(file.toString())) {
            assertEquals("CERTIFICATE", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (InputStream in = fileSystem.getResource(LocalFileSystem.CLASSPATH_PROTOCOL + "application.yml")) {
            assertTrue(in.readAllBytes().length > 0);
        }

        assertThrows(FileNotFoundException.class,
                () -> fileSystem.getResource(LocalFileSystem.CLASSPATH_PROTOCOL + "missing-imap.yml"));
        assertThrows(FileNotFoundException.class, () -> fileSystem.getFile(tempDir.toString()));
        assertThrows(FileNotFoundException.class,
                () -> fileSystem.getFile(LocalFileSystem.FILE_PROTOCOL_AND_CONF + "missing-corvex-imap.pem"));
        assertThrows(FileNotFoundException.class,
                () -> fileSystem.getFile(LocalFileSystem.FILE_PROTOCOL_AND_VAR + "missing-corvex-imap.pem"));
        assertThrows(FileNotFoundException.class,
                () -> fileSystem.getFile(LocalFileSystem.FILE_PROTOCOL_ABSOLUTE + "missing-corvex-imap.pem"));
    }

    @Test
    void jamesConfigBuildsTheCompleteManualSpringWiring() {
        JamesImapConfig config = new JamesImapConfig();
        assertSame(NoopMetricFactory.INSTANCE, config.jamesMetricFactory());

        EventBus eventBus = config.jamesEventBus(config.jamesMetricFactory());
        assertTrue(eventBus instanceof InVMEventBus);
        CorvexMailboxManager manager = config.corvexMailboxManager(
                mock(ImapAuthClient.class), mock(ImapStorageClient.class), eventBus);
        assertNotNull(manager);
        SubscriptionManager subscriptions = config.corvexSubscriptionManager(manager);
        assertTrue(subscriptions instanceof CorvexSubscriptionManager);
        CorvexQuotaRootResolver quotaRoots = config.corvexQuotaRootResolver(manager);
        assertNotNull(quotaRoots);
        assertTrue(config.corvexQuotaManager(manager, quotaRoots) instanceof CorvexQuotaManager);

        ImapProcessor processor = config.jamesImapProcessor(
                manager, eventBus, subscriptions,
                config.corvexQuotaManager(manager, quotaRoots), quotaRoots, config.jamesMetricFactory());
        assertNotNull(processor, "DefaultImapProcessorFactory должен собрать цепочку без Guice");
    }
}
