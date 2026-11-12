package ru.corvex.mailstorage.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import ru.corvex.mailstorage.api.dto.MailDtos;
import ru.corvex.mailstorage.api.dto.ProtocolDtos;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.MailFolder;
import ru.corvex.mailstorage.domain.MessageMetadata;
import ru.corvex.mailstorage.mime.MimeMailParser;
import ru.corvex.mailstorage.repository.MailFolderRepository;
import ru.corvex.mailstorage.repository.MessageMetadataRepository;
import ru.corvex.mailstorage.storage.MaildirStorage;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @ParameterizedTest(name = "protocolFlags={0}")
    @ValueSource(booleans = {false, true})
    void webOrProtocolFlagsAndMoveKeepTheCommittedMessageAndFile(boolean protocolFlags) throws Exception {
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
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch moveAtLock = new CountDownLatch(1);
        AtomicInteger flagsPid = new AtomicInteger();
        AtomicInteger movePid = new AtomicInteger();
        MessageMetadataRepository flagsRepo = (MessageMetadataRepository) Proxy.newProxyInstance(
                MessageMetadataRepository.class.getClassLoader(), new Class<?>[]{MessageMetadataRepository.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(messages, args);
                        if (method.getName().equals("findByIdAndOwnerId") || method.getName().equals("lockOwned")
                                || method.getName().equals("findByFolder_IdAndUidAndOwnerId")
                                || method.getName().equals("lockFolderUid")) {
                            flagsPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                            loaded.countDown();
                            if (!resume.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Flag pause timed out");
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        MessageMetadataRepository moveRepo = (MessageMetadataRepository) Proxy.newProxyInstance(
                MessageMetadataRepository.class.getClassLoader(), new Class<?>[]{MessageMetadataRepository.class},
                (proxy, method, args) -> {
                    try {
                        if (method.getName().equals("lockOwned")) {
                            movePid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                            moveAtLock.countDown();
                        }
                        return method.invoke(messages, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        MailApiService flagService = new MailApiService(folders, flagsRepo, mock(MailDeliveryService.class),
                storage, new MimeMailParser(storage), jdbc, mock(ClamAvClient.class));
        ProtocolMailService protocolService = new ProtocolMailService(folders, flagsRepo, mock(MailDeliveryService.class),
                storage, mock(RawMessageSpool.class), new MimeMailParser(storage), mock(AuthUserClient.class));
        MailApiService moveService = new MailApiService(folders, moveRepo, mock(MailDeliveryService.class),
                storage, new MimeMailParser(storage), jdbc, mock(ClamAvClient.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Pause the selected real flag handler after its repository read/lock.
            Future<?> flags = executor.submit(() -> tx.executeWithoutResult(status -> {
                if (protocolFlags) {
                    protocolService.updateFlags(owner, ids[0], 1L, new ProtocolDtos.FlagsUpdate(true, null, null, null, null));
                } else {
                    flagService.updateFlags(owner, ids[2], new MailDtos.UpdateFlagsRequest(true, null, null, null));
                }
            }));
            assertTrue(loaded.await(30, TimeUnit.SECONDS), "Flag handler did not load the message");
            Future<?> move = executor.submit(() -> tx.executeWithoutResult(status -> {
                try {
                    moveService.moveMessage(owner, ids[2], ids[1], ids[0], 1L);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }));
            assertTrue(moveAtLock.await(30, TimeUnit.SECONDS), "MOVE did not reach message lock");
            // Wait until MOVE finishes or PostgreSQL confirms it is blocked by flags.
            boolean blocked = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!move.isDone() && System.nanoTime() < deadline) {
                blocked = Boolean.TRUE.equals(jdbc.queryForObject(
                        "select ? = any(pg_blocking_pids(?))", Boolean.class, flagsPid.get(), movePid.get()));
                if (blocked) break;
                Thread.sleep(25);
            }
            assertTrue(blocked || move.isDone(), "MOVE neither completed nor waited for the flag transaction");
            if (move.isDone()) move.get(30, TimeUnit.SECONDS); // propagate failure before releasing flags
            resume.countDown();
            flags.get(30, TimeUnit.SECONDS);
            move.get(30, TimeUnit.SECONDS);
            tx.executeWithoutResult(status -> {
                MessageMetadata current = messages.findByIdAndOwnerId(ids[2], owner).orElseThrow();
                assertEquals(ids[1], current.getFolder().getId(), "A stale flag update reverted the folder");
                assertNotEquals(original, current.getMaildirPath(), "A stale flag update reverted the path");
                assertTrue(current.isSeen());
                Path persisted = temp.resolve("mail").resolve(current.getMaildirPath());
                assertTrue(Files.isRegularFile(persisted), "The database path must exist");
                try {
                    assertArrayEquals(raw, Files.readAllBytes(persisted));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Transactions did not stop");
        }
    }
    @ParameterizedTest(name = "expunge={0}")
    @ValueSource(booleans = {true, false})
    void rolledBackProtocolDeletionKeepsDatabaseAndFile(boolean expunge) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID owner = UUID.randomUUID();
        MaildirStorage storage = new MaildirStorage(temp.resolve("mail"), temp.resolve("attachments"));
        byte[] raw = "From: test@example.test\r\n\r\nsynthetic rollback message\r\n".getBytes(StandardCharsets.UTF_8);
        String original = storage.storeMessage("owner@example.test", "Inbox", new ByteArrayInputStream(raw)).toString();
        UUID[] ids = tx.execute(status -> {
            MailFolder folder = new MailFolder();
            folder.setOwnerId(owner); folder.setName("Inbox"); folder.setSpecialUse("INBOX");
            folder = folders.saveAndFlush(folder);
            MessageMetadata message = new MessageMetadata();
            message.setOwnerId(owner); message.setFolder(folder); message.setUid(1);
            message.setSender("test@example.test"); message.setSubject("synthetic rollback");
            message.setInternalDate(Instant.now()); message.setSizeBytes(raw.length);
            message.setMaildirPath(original); message.setDeleted(true);
            message = messages.saveAndFlush(message);
            return new UUID[]{folder.getId(), message.getId()};
        });
        assertNotNull(ids);
        ProtocolMailService service = new ProtocolMailService(folders, messages, mock(MailDeliveryService.class),
                storage, mock(RawMessageSpool.class), new MimeMailParser(storage), mock(AuthUserClient.class));
        tx.executeWithoutResult(status -> {
            if (expunge) service.expunge(owner, ids[0], new ProtocolDtos.ExpungeRequest(List.of(1L)));
            else service.hardDeleteMessage(owner, ids[1]);
            status.setRollbackOnly(); // Force DB rollback after the real deletion handler returns.
        });
        tx.executeWithoutResult(status -> {
            MessageMetadata current = messages.findByIdAndOwnerId(ids[1], owner).orElseThrow();
            assertEquals(original, current.getMaildirPath());
        });
        Path file = temp.resolve("mail").resolve(original);
        assertTrue(Files.isRegularFile(file), "Rollback left a message row referencing a missing file");
        assertArrayEquals(raw, Files.readAllBytes(file));
    }

    @ParameterizedTest(name = "expunge={0}")
    @ValueSource(booleans = {true, false})
    void committedProtocolDeletionRemovesDatabaseAndFile(boolean expunge) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID owner = UUID.randomUUID();
        MaildirStorage storage = new MaildirStorage(temp.resolve("mail"), temp.resolve("attachments"));
        byte[] raw = "From: test@example.test\r\n\r\nsynthetic rollback message\r\n".getBytes(StandardCharsets.UTF_8);
        String original = storage.storeMessage("owner@example.test", "Inbox", new ByteArrayInputStream(raw)).toString();
        UUID[] ids = tx.execute(status -> {
            MailFolder folder = new MailFolder();
            folder.setOwnerId(owner); folder.setName("Inbox"); folder.setSpecialUse("INBOX");
            folder = folders.saveAndFlush(folder);
            MessageMetadata message = new MessageMetadata();
            message.setOwnerId(owner); message.setFolder(folder); message.setUid(1);
            message.setSender("test@example.test"); message.setSubject("synthetic rollback");
            message.setInternalDate(Instant.now()); message.setSizeBytes(raw.length);
            message.setMaildirPath(original); message.setDeleted(true);
            message = messages.saveAndFlush(message);
            return new UUID[]{folder.getId(), message.getId()};
        });
        assertNotNull(ids);
        ProtocolMailService service = new ProtocolMailService(folders, messages, mock(MailDeliveryService.class),
                storage, mock(RawMessageSpool.class), new MimeMailParser(storage), mock(AuthUserClient.class));
        tx.executeWithoutResult(status -> {
            if (expunge) service.expunge(owner, ids[0], new ProtocolDtos.ExpungeRequest(List.of(1L)));
            else service.hardDeleteMessage(owner, ids[1]);
        });
        tx.executeWithoutResult(status -> {
            assertTrue(messages.findByIdAndOwnerId(ids[1], owner).isEmpty());
        });
        Path file = temp.resolve("mail").resolve(original);
        assertFalse(Files.exists(file), "Committed deletion did not clean up its message file");
    }

    @Test
    void rolledBackFolderDeleteKeepsMessagesAndDirectory() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID owner = UUID.randomUUID();
        MaildirStorage storage = new MaildirStorage(temp.resolve("mail"), temp.resolve("attachments"));
        byte[] raw = "From: test@example.test\r\n\r\nfolder rollback\r\n".getBytes(StandardCharsets.UTF_8);
        String original = storage.storeMessage("owner@example.test", "Projects", new ByteArrayInputStream(raw)).toString();
        UUID[] ids = tx.execute(status -> {
            MailFolder folder = new MailFolder();
            folder.setOwnerId(owner); folder.setName("Projects"); folder = folders.saveAndFlush(folder);
            MessageMetadata message = new MessageMetadata();
            message.setOwnerId(owner); message.setFolder(folder); message.setUid(1);
            message.setSender("test@example.test"); message.setSubject("folder rollback");
            message.setInternalDate(Instant.now()); message.setSizeBytes(raw.length); message.setMaildirPath(original);
            message = messages.saveAndFlush(message);
            return new UUID[]{folder.getId(), message.getId()};
        });
        assertNotNull(ids);
        AuthUserClient auth = mock(AuthUserClient.class);
        when(auth.findById(owner)).thenReturn(Optional.of(
                new AuthUserClient.MailboxOwner(owner, "owner@example.test", "Test", "ACTIVE", 0)));
        ProtocolMailService service = new ProtocolMailService(folders, messages, mock(MailDeliveryService.class),
                storage, mock(RawMessageSpool.class), new MimeMailParser(storage), auth);
        tx.executeWithoutResult(status -> {
            service.deleteFolder(owner, ids[0]);
            status.setRollbackOnly();
        });
        tx.executeWithoutResult(status -> {
            assertTrue(folders.findByIdAndOwnerId(ids[0], owner).isPresent());
            assertTrue(messages.findByIdAndOwnerId(ids[1], owner).isPresent());
        });
        Path file = temp.resolve("mail").resolve(original);
        assertTrue(Files.isRegularFile(file), "Folder rollback left a message referencing a missing file");
        assertArrayEquals(raw, Files.readAllBytes(file));
    }

    @Test
    void deletingOneOfTwoReferencesKeepsSharedMessageFile() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID owner = UUID.randomUUID();
        MaildirStorage storage = new MaildirStorage(temp.resolve("mail"), temp.resolve("attachments"));
        byte[] raw = "From: test@example.test\r\n\r\nshared path\r\n".getBytes(StandardCharsets.UTF_8);
        String original = storage.storeMessage("owner@example.test", "Inbox", new ByteArrayInputStream(raw)).toString();
        UUID[] ids = tx.execute(status -> {
            MailFolder folder = new MailFolder();
            folder.setOwnerId(owner); folder.setName("Inbox"); folder.setSpecialUse("INBOX");
            folder = folders.saveAndFlush(folder);
            UUID[] result = new UUID[2];
            for (int i = 0; i < 2; i++) {
                MessageMetadata m = new MessageMetadata();
                m.setOwnerId(owner); m.setFolder(folder); m.setUid(i + 1);
                m.setSender("test@example.test"); m.setSubject("shared path");
                m.setInternalDate(Instant.now()); m.setSizeBytes(raw.length); m.setMaildirPath(original);
                result[i] = messages.saveAndFlush(m).getId();
            }
            return result;
        });
        assertNotNull(ids);
        ProtocolMailService service = new ProtocolMailService(folders, messages, mock(MailDeliveryService.class),
                storage, mock(RawMessageSpool.class), new MimeMailParser(storage), mock(AuthUserClient.class));
        tx.executeWithoutResult(status -> service.hardDeleteMessage(owner, ids[0]));
        tx.executeWithoutResult(status -> {
            assertTrue(messages.findByIdAndOwnerId(ids[0], owner).isEmpty());
            assertEquals(original, messages.findByIdAndOwnerId(ids[1], owner).orElseThrow().getMaildirPath());
        });
        Path file = temp.resolve("mail").resolve(original);
        assertTrue(Files.isRegularFile(file), "Deletion removed a file still referenced by another message");
        assertArrayEquals(raw, Files.readAllBytes(file));
    }

    @Test
    void movingOneOfTwoReferencesKeepsOtherMessageReadable() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID owner = UUID.randomUUID();
        MaildirStorage storage = new MaildirStorage(temp.resolve("mail"), temp.resolve("attachments"));
        byte[] raw = "From: test@example.test\r\n\r\nshared move\r\n".getBytes(StandardCharsets.UTF_8);
        String original = storage.storeMessage("owner@example.test", "Junk", new ByteArrayInputStream(raw)).toString();
        UUID[] ids = tx.execute(status -> {
            MailFolder source = new MailFolder();
            source.setOwnerId(owner); source.setName("Junk"); source.setSpecialUse("SPAM");
            source = folders.saveAndFlush(source);
            MailFolder target = new MailFolder();
            target.setOwnerId(owner); target.setName("Trash"); target.setSpecialUse("TRASH");
            target = folders.saveAndFlush(target);
            UUID[] result = new UUID[]{source.getId(), target.getId(), null, null};
            for (int i = 0; i < 2; i++) {
                MessageMetadata m = new MessageMetadata();
                m.setOwnerId(owner); m.setFolder(source); m.setUid(i + 1); m.setSender("test@example.test");
                m.setSubject("shared move"); m.setInternalDate(Instant.now()); m.setSizeBytes(raw.length);
                m.setMaildirPath(original); result[i + 2] = messages.saveAndFlush(m).getId();
            }
            return result;
        });
        assertNotNull(ids);
        MailApiService service = new MailApiService(folders, messages, mock(MailDeliveryService.class),
                storage, new MimeMailParser(storage), jdbc, mock(ClamAvClient.class));
        tx.executeWithoutResult(status -> {
            try {
                service.moveMessage(owner, ids[2], ids[1], ids[0], 1L);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        tx.executeWithoutResult(status -> {
            assertEquals(original, messages.findByIdAndOwnerId(ids[3], owner).orElseThrow().getMaildirPath());
            assertEquals(ids[1], messages.findByIdAndOwnerId(ids[2], owner).orElseThrow().getFolder().getId());
        });
        Path file = temp.resolve("mail").resolve(original);
        assertTrue(Files.isRegularFile(file), "MOVE removed a file still referenced by another message");
        assertArrayEquals(raw, Files.readAllBytes(file));
    }

    @Test
    void rolledBackRenameKeepsOriginalFolderAndMessage() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID owner = UUID.randomUUID();
        MaildirStorage storage = new MaildirStorage(temp.resolve("mail"), temp.resolve("attachments"));
        byte[] raw = "From: test@example.test\r\n\r\nrename rollback\r\n".getBytes(StandardCharsets.UTF_8);
        String original = storage.storeMessage("owner@example.test", "Projects", new ByteArrayInputStream(raw)).toString();
        UUID[] ids = tx.execute(status -> {
            MailFolder folder = new MailFolder();
            folder.setOwnerId(owner); folder.setName("Projects"); folder = folders.saveAndFlush(folder);
            MessageMetadata m = new MessageMetadata();
            m.setOwnerId(owner); m.setFolder(folder); m.setUid(1); m.setSender("test@example.test");
            m.setSubject("rename rollback"); m.setInternalDate(Instant.now()); m.setSizeBytes(raw.length);
            m.setMaildirPath(original); m = messages.saveAndFlush(m);
            return new UUID[]{folder.getId(), m.getId()};
        });
        assertNotNull(ids);
        AuthUserClient auth = mock(AuthUserClient.class);
        when(auth.findById(owner)).thenReturn(Optional.of(
                new AuthUserClient.MailboxOwner(owner, "owner@example.test", "Test", "ACTIVE", 0)));
        ProtocolMailService service = new ProtocolMailService(folders, messages, mock(MailDeliveryService.class),
                storage, mock(RawMessageSpool.class), new MimeMailParser(storage), auth);
        tx.executeWithoutResult(status -> {
            try {
                service.renameFolder(owner, ids[0], new ProtocolDtos.RenameFolderRequest("Renamed", null, false));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            status.setRollbackOnly();
        });
        tx.executeWithoutResult(status -> {
            assertEquals("Projects", folders.findByIdAndOwnerId(ids[0], owner).orElseThrow().getName());
            assertEquals(original, messages.findByIdAndOwnerId(ids[1], owner).orElseThrow().getMaildirPath());
        });
        Path file = temp.resolve("mail").resolve(original);
        assertTrue(Files.isRegularFile(file), "Rename rollback left a message referencing a missing file");
        assertArrayEquals(raw, Files.readAllBytes(file));
    }

}
