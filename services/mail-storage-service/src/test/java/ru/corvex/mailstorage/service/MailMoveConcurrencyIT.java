package ru.corvex.mailstorage.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.corvex.mailstorage.av.ClamAvClient;
import ru.corvex.mailstorage.domain.MailFolder;
import ru.corvex.mailstorage.domain.MessageMetadata;
import ru.corvex.mailstorage.mime.MimeMailParser;
import ru.corvex.mailstorage.repository.MailFolderRepository;
import ru.corvex.mailstorage.repository.MessageMetadataRepository;
import ru.corvex.mailstorage.storage.MaildirStorage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Opt-in diagnostic. PostgreSQL and files are isolated and contain only synthetic data. */
@EnabledIfSystemProperty(named = "mail.move.it", matches = "true")
@Testcontainers
@ContextConfiguration(classes = MailMoveConcurrencyIT.JpaTestApplication.class)
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MailMoveConcurrencyIT {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = MailFolder.class)
    @EnableJpaRepositories(basePackageClasses = MailFolderRepository.class)
    static class JpaTestApplication { }

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withEnv("POSTGRES_DB", "mail_db")
            .withEnv("POSTGRES_USER", "corvex_test")
            .withEnv("POSTGRES_PASSWORD", "temporary_test_password")
            .withExposedPorts(5432)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/mail_db");
        registry.add("spring.datasource.username", () -> "corvex_test");
        registry.add("spring.datasource.password", () -> "temporary_test_password");
    }

    @Autowired MailFolderRepository folders;
    @Autowired MessageMetadataRepository messages;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @TempDir Path temp;

    @Test
    void staleFlagUpdateMustNotRestoreOldPathAfterMove() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID owner = UUID.randomUUID();
        MaildirStorage storage = new MaildirStorage(temp.resolve("mail"), temp.resolve("attachments"));
        byte[] raw = "From: test@example.test\r\n\r\nsynthetic message\r\n".getBytes(StandardCharsets.UTF_8);
        String original = storage.storeMessage("owner@example.test", "Junk", new ByteArrayInputStream(raw)).toString();
        UUID[] ids = tx.execute(status -> {
            MailFolder source = new MailFolder();
            source.setOwnerId(owner); source.setName("Junk"); source.setSpecialUse("SPAM");
            source = folders.saveAndFlush(source);
            MailFolder target = new MailFolder();
            target.setOwnerId(owner); target.setName("Trash"); target.setSpecialUse("TRASH");
            target = folders.saveAndFlush(target);
            MessageMetadata m = new MessageMetadata();
            m.setOwnerId(owner); m.setFolder(source); m.setUid(1); m.setSender("test@example.test");
            m.setSubject("synthetic"); m.setInternalDate(Instant.now()); m.setSizeBytes(raw.length);
            m.setMaildirPath(original); m = messages.saveAndFlush(m);
            return new UUID[]{source.getId(), target.getId(), m.getId()};
        });
        assertNotNull(ids);
        MailApiService service = new MailApiService(folders, messages, mock(MailDeliveryService.class),
                storage, new MimeMailParser(storage), jdbc, mock(ClamAvClient.class));
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // Reproduce updateFlags' vulnerable ordering using a real, separate JPA transaction.
            // This intentionally pauses after loading the entity, before taking the folder lock.
            Future<?> flags = executor.submit(() -> tx.executeWithoutResult(status -> {
                MessageMetadata m = messages.findByIdAndOwnerId(ids[2], owner).orElseThrow();
                m.setSeen(true);
                loaded.countDown();
                try {
                    if (!resume.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for move");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                MailFolder folder = folders.lockById(m.getFolder().getId()).orElseThrow();
                long modseq = Math.max(1, folder.getHighestModseq()) + 1;
                folder.setHighestModseq(modseq);
                folders.save(folder);
                m.setModseq(modseq);
                messages.save(m);
            }));
            assertTrue(loaded.await(30, TimeUnit.SECONDS), "Flag transaction did not load message");
            tx.executeWithoutResult(status -> {
                try {
                    service.moveMessage(owner, ids[2], ids[1], ids[0], 1L);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            resume.countDown();
            flags.get(30, TimeUnit.SECONDS);
            tx.executeWithoutResult(status -> {
                MessageMetadata current = messages.findByIdAndOwnerId(ids[2], owner).orElseThrow();
                assertEquals(ids[1], current.getFolder().getId(), "A stale flag update reverted the folder");
                assertNotEquals(original, current.getMaildirPath(), "A stale flag update reverted the path");
                assertTrue(Files.isRegularFile(temp.resolve("mail").resolve(current.getMaildirPath())),
                        "The path persisted in the database must still exist");
            });
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Flag transaction did not stop");
        }
    }
}
