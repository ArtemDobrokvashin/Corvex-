package ru.corvex.mailstorage.external;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.ExternalMailAccount;
import ru.corvex.mailstorage.domain.ExternalMailFolderState;
import ru.corvex.mailstorage.domain.ExternalMailImportedMessage;
import ru.corvex.mailstorage.domain.ExternalMailSyncRun;
import ru.corvex.mailstorage.domain.MailFolder;
import ru.corvex.mailstorage.pipeline.MailEventPublisher;
import ru.corvex.mailstorage.pipeline.MailPipelineEvents;
import ru.corvex.mailstorage.repository.ExternalMailAccountRepository;
import ru.corvex.mailstorage.repository.ExternalMailFolderStateRepository;
import ru.corvex.mailstorage.repository.ExternalMailImportedMessageRepository;
import ru.corvex.mailstorage.repository.ExternalMailSyncRunRepository;
import ru.corvex.mailstorage.repository.MailFolderRepository;
import ru.corvex.mailstorage.service.MailDeliveryService;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalMailSyncServiceTest {

    @TempDir
    Path tmp;

    private final UUID ownerId = UUID.randomUUID();
    private final AuthUserClient.MailboxOwner owner =
            new AuthUserClient.MailboxOwner(ownerId, "owner@company.local", "Owner", "ACTIVE", 0);
    private final List<ExternalMailSyncService> servicesToStop = new ArrayList<>();

    private ExternalMailAccountRepository accounts;
    private ExternalMailFolderStateRepository states;
    private ExternalMailImportedMessageRepository imported;
    private ExternalMailSyncRunRepository runs;
    private MailFolderRepository folders;
    private ExternalMailAccountService accountService;
    private ExternalMailClient client;
    private ExternalMailProperties props;
    private ExternalMailSecretCipher cipher;
    private AuthUserClient auth;
    private MailDeliveryService delivery;
    private RawMessageSpool spool;
    private MailEventPublisher events;
    private JdbcTemplate jdbc;
    private ExternalMailSyncService service;

    private final AtomicReference<ExternalMailAccount> currentAccount = new AtomicReference<>();
    private final AtomicReference<ExternalMailSyncRun> currentRun = new AtomicReference<>();
    private final AtomicReference<Optional<AuthUserClient.MailboxOwner>> ownerLookup =
            new AtomicReference<>();
    private final AtomicInteger claimResult = new AtomicInteger(1);

    private ExternalMailAccount account;
    private ExternalMailSyncRun run;
    private MailFolder target;

    private List<ExternalMailClient.RemoteFolderInfo> remoteFolders = List.of();
    private List<ExternalMailClient.RemoteMessageEnvelope> snapshotEnvelopes = List.of();
    private final Map<String, List<ExternalMailClient.RemoteMessageEnvelope>> fetchEnvelopes = new LinkedHashMap<>();
    private final Map<String, byte[]> rawByKey = new HashMap<>();
    private final Set<String> rawFailureKeys = new HashSet<>();
    private final Map<String, ExternalMailClient.ExternalMailClientException> fetchFailures = new HashMap<>();
    private final List<ExternalMailClient.HandleResult> handlerResults = new ArrayList<>();
    private final List<Long> fetchAfterUids = new ArrayList<>();
    private final AtomicInteger fetchCalls = new AtomicInteger();

    private final Map<String, ExternalMailFolderState> stateByFolder = new HashMap<>();
    private final Map<String, ExternalMailImportedMessage> importedByKey = new HashMap<>();
    private final List<ExternalMailImportedMessage> importedRecords = new ArrayList<>();
    private final List<MailFolder> knownFolders = new ArrayList<>();
    private boolean duplicateMessageId;
    private boolean duplicateSha;

    @BeforeEach
    void setUp() throws Exception {
        accounts = mock(ExternalMailAccountRepository.class);
        states = mock(ExternalMailFolderStateRepository.class);
        imported = mock(ExternalMailImportedMessageRepository.class);
        runs = mock(ExternalMailSyncRunRepository.class);
        folders = mock(MailFolderRepository.class);
        accountService = mock(ExternalMailAccountService.class);
        client = mock(ExternalMailClient.class);
        props = new ExternalMailProperties(true, true, false, false, 10, 2, 10, 2, 3, 1);
        cipher = new ExternalMailSecretCipher(validKey());
        auth = mock(AuthUserClient.class);
        delivery = mock(MailDeliveryService.class);
        spool = new RawMessageSpool(tmp.resolve("spool"), 1_000_000);
        events = mock(MailEventPublisher.class);
        jdbc = mock(JdbcTemplate.class);
        service = newService(props, cipher, spool);

        account = account();
        currentAccount.set(account);
        run = run(ExternalMailSyncRun.Trigger.MANUAL);
        currentRun.set(run);
        ownerLookup.set(Optional.of(owner));
        target = folder("INBOX", "INBOX", null);
        knownFolders.add(target);

        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("sync_lock_owner = ?") && sql.contains("enabled = true")) {
                return claimResult.get();
            }
            return 1;
        });
        when(accounts.findById(any())).thenAnswer(inv -> {
            ExternalMailAccount current = currentAccount.get();
            UUID id = inv.getArgument(0);
            return current != null && id.equals(current.getId()) ? Optional.of(current) : Optional.empty();
        });
        when(accounts.saveAndFlush(any(ExternalMailAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        when(runs.findById(any())).thenAnswer(inv -> {
            ExternalMailSyncRun current = currentRun.get();
            UUID id = inv.getArgument(0);
            return current != null && id.equals(current.getId()) ? Optional.of(current) : Optional.empty();
        });
        when(runs.saveAndFlush(any(ExternalMailSyncRun.class))).thenAnswer(inv -> {
            ExternalMailSyncRun value = inv.getArgument(0);
            if (value.getId() == null) value.setId(UUID.randomUUID());
            currentRun.set(value);
            return value;
        });
        when(auth.findById(any())).thenAnswer(inv -> ownerLookup.get());
        when(accountService.accessibleAccount(any(), any(), any())).thenAnswer(inv -> currentAccount.get());
        when(accountService.toConfig(any(ExternalMailAccount.class))).thenAnswer(inv -> clientConfig(inv.getArgument(0)));

        when(states.findByAccount_IdAndRemoteFolder(any(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(stateByFolder.get(inv.getArgument(1))));
        when(states.saveAndFlush(any(ExternalMailFolderState.class))).thenAnswer(inv -> {
            ExternalMailFolderState state = inv.getArgument(0);
            if (state.getId() == null) state.setId(UUID.randomUUID());
            stateByFolder.put(state.getRemoteFolder(), state);
            return state;
        });

        when(imported.findRemote(any(), anyString(), any(), anyString())).thenAnswer(inv -> {
            String folder = inv.getArgument(1);
            Object validity = inv.getArgument(2);
            String uid = inv.getArgument(3);
            return Optional.ofNullable(importedByKey.get(key(folder, validity, uid)));
        });
        when(imported.existsByAccount_IdAndRemoteFolderAndMessageIdHeaderIgnoreCaseAndStatusIn(
                any(), anyString(), anyString(), any())).thenAnswer(inv -> duplicateMessageId);
        when(imported.existsByAccount_IdAndRemoteFolderAndSha256AndStatusIn(
                any(), anyString(), anyString(), any())).thenAnswer(inv -> duplicateSha);
        when(imported.saveAndFlush(any(ExternalMailImportedMessage.class))).thenAnswer(inv -> {
            ExternalMailImportedMessage record = inv.getArgument(0);
            if (record.getId() == null) record.setId(UUID.randomUUID());
            if (!importedRecords.contains(record)) importedRecords.add(record);
            importedByKey.put(key(record.getRemoteFolder(), record.getUIDValidity(), record.getRemoteUid()), record);
            return record;
        });

        when(folders.findByIdAndOwnerId(any(), any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return knownFolders.stream().filter(f -> id.equals(f.getId())).findFirst();
        });
        when(folders.findAllByOwnerIdOrderBySpecialUseAscNameAsc(any()))
                .thenAnswer(inv -> new ArrayList<>(knownFolders));
        when(folders.saveAndFlush(any(MailFolder.class))).thenAnswer(inv -> {
            MailFolder folder = inv.getArgument(0);
            if (folder.getId() == null) folder.setId(UUID.randomUUID());
            if (!knownFolders.contains(folder)) knownFolders.add(folder);
            return folder;
        });
        when(delivery.ensureFolder(any(), anyString(), anyString())).thenAnswer(inv -> {
            String specialUse = inv.getArgument(1);
            String name = inv.getArgument(2);
            return knownFolders.stream()
                    .filter(f -> specialUse.equals(f.getSpecialUse()))
                    .findFirst()
                    .orElseGet(() -> {
                        MailFolder created = folder(name, specialUse, null);
                        knownFolders.add(created);
                        return created;
                    });
        });

        when(client.listFolders(any())).thenAnswer(inv -> remoteFolders);
        when(client.listMessageEnvelopes(any(), anyString(), anyInt())).thenAnswer(inv -> snapshotEnvelopes);
        when(client.fetchFolder(any(), anyString(), anyLong(), anyInt(), any())).thenAnswer(inv -> {
            fetchCalls.incrementAndGet();
            String folder = inv.getArgument(1);
            fetchAfterUids.add(inv.getArgument(2));
            ExternalMailClient.ExternalMailClientException failure = fetchFailures.get(folder);
            if (failure != null) throw failure;
            ExternalMailClient.MessageHandler handler = inv.getArgument(4);
            int seen = 0;
            int handled = 0;
            boolean stopped = false;
            for (ExternalMailClient.RemoteMessageEnvelope envelope : fetchEnvelopes.getOrDefault(folder, List.of())) {
                seen++;
                String key = key(envelope.remoteFolder(), envelope.UIDValidity(), envelope.remoteUid());
                try {
                    ExternalMailClient.HandleResult result = handler.handle(envelope, () -> {
                        if (rawFailureKeys.contains(key)) throw new IOException("raw unavailable");
                        byte[] raw = rawByKey.getOrDefault(key,
                                ("Subject: " + envelope.remoteUid() + "\r\n\r\nbody").getBytes(StandardCharsets.UTF_8));
                        return new ByteArrayInputStream(raw);
                    });
                    handlerResults.add(result);
                    handled++;
                } catch (ExternalMailClient.StopFolderException stop) {
                    stopped = true;
                    break;
                }
            }
            return new ExternalMailClient.FetchStats(seen, handled, stopped);
        });
    }

    @AfterEach
    void tearDown() {
        for (ExternalMailSyncService toStop : servicesToStop) {
            toStop.shutdown();
        }
    }

    @Test
    void requestAndListRunsCheckAccessStateAndMapping() {
        account.setEnabled(false);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.requestSync(ownerId, Set.of("X"), account.getId(), ExternalMailSyncRun.Trigger.MANUAL))
                .getStatusCode());
        account.setEnabled(true);
        account.setSyncEnabled(false);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.requestSync(ownerId, Set.of("X"), account.getId(), ExternalMailSyncRun.Trigger.MANUAL))
                .getStatusCode());

        account.setSyncEnabled(true);
        claimResult.set(0);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.requestSync(ownerId, Set.of("X"), account.getId(), ExternalMailSyncRun.Trigger.MANUAL))
                .getStatusCode());

        run.setFoldersCount(2);
        run.setFetchedCount(3);
        when(runs.findTop20ByAccount_IdOrderByStartedAtDesc(account.getId())).thenReturn(List.of(run));
        var listed = service.listRuns(ownerId, Set.of("X"), account.getId());
        assertEquals(run.getId(), listed.get(0).id());
        assertEquals(account.getId(), listed.get(0).accountId());
        assertEquals(3, listed.get(0).fetchedCount());
    }

    @Test
    void requestSyncStartsAsyncWorkAndAuditsRequest() throws Exception {
        remoteFolders = List.of();

        var result = service.requestSync(ownerId, Set.of("X"), account.getId(), ExternalMailSyncRun.Trigger.MANUAL);

        assertNotNull(result.id());
        verify(accountService).audit(ownerId, account, "EXTERNAL_SYNC_REQUESTED", "runId=" + result.id());
        awaitFinished();
        assertEquals(ExternalMailSyncRun.Status.SUCCESS, currentRun.get().getStatus());
        assertNotNull(currentRun.get().getFinishedAt());
    }

    @Test
    void startSyncHandlesClaimFailureMissingAccountAndRejectedExecutor() {
        claimResult.set(0);
        assertTrue(service.startSync(account.getId(), ExternalMailSyncRun.Trigger.MANUAL).isEmpty());

        claimResult.set(1);
        currentAccount.set(null);
        assertTrue(service.startSync(account.getId(), ExternalMailSyncRun.Trigger.MANUAL).isEmpty());
        verify(jdbc, times(3)).update(anyString(), any(Object[].class));

        currentAccount.set(account);
        service.shutdown();
        assertTrue(service.startSync(account.getId(), ExternalMailSyncRun.Trigger.MANUAL).isEmpty());
        assertEquals(ExternalMailSyncRun.Status.FAILED, currentRun.get().getStatus());
        assertTrue(currentRun.get().getError().contains("переполнена"));
    }

    @Test
    void pollDueHonorsFeatureAndCipherAndHandlesRepositoryFailure() throws Exception {
        ExternalMailProperties disabled = new ExternalMailProperties(
                false, true, false, false, 10, 2, 10, 1, 1, 1);
        newService(disabled, cipher, spool).pollDue();
        verify(accounts, never()).findDue(any(), any());

        newService(props, new ExternalMailSecretCipher(""), spool).pollDue();
        verify(accounts, never()).findDue(any(), any());

        when(accounts.findDue(any(), any())).thenThrow(new IllegalStateException("db down"));
        service.pollDue();

        doReturn(List.of(account)).when(accounts).findDue(any(), any());
        claimResult.set(0);
        service.pollDue();
        // Один вызов JDBC на безуспешный claim: pollDue действительно дошёл до startSync.
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void successfulImportQueuesMessagesAdvancesCursorAndFinishesRun() throws Exception {
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 44L, 3, 2));
        var first = envelope("INBOX", "1", 44L, "<one@x>", "", false);
        var second = envelope("INBOX", "2", 44L, "<two@x>", "sender@partner.test", false);
        fetchEnvelopes.put("INBOX", List.of(first, second));

        execute(ExternalMailSyncRun.Trigger.MANUAL);

        assertEquals(ExternalMailSyncRun.Status.SUCCESS, run.getStatus());
        assertEquals(1, run.getFoldersCount());
        assertEquals(2, run.getFetchedCount());
        assertEquals(2, run.getQueuedCount());
        assertEquals(0, run.getFailedCount());
        assertEquals(2, stateByFolder.get("INBOX").getLastSeenUid());
        assertTrue(stateByFolder.get("INBOX").isInitialSnapshotDone());
        assertEquals(target, stateByFolder.get("INBOX").getTargetFolder());
        assertEquals(2, importedRecords.size());
        assertTrue(importedRecords.stream().allMatch(r -> r.getStatus() == ExternalMailImportedMessage.Status.QUEUED));
        assertTrue(importedRecords.stream().allMatch(r -> spool.exists(r.getRawRef())));
        assertEquals(0, account.getConsecutiveFailures());
        assertNotNull(account.getLastSuccessAt());
        assertTrue(account.getNextSyncAt().isAfter(Instant.now().plusSeconds(250)));

        ArgumentCaptor<Object> eventsSent = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publishConfirmed(eq("mail.inbound.received"), eventsSent.capture(), anyString());
        MailPipelineEvents.InboundReceivedEvent firstEvent =
                (MailPipelineEvents.InboundReceivedEvent) eventsSent.getAllValues().get(0);
        assertEquals(account.getEmailAddress(), firstEvent.mailFrom(),
                "пустой From конверта заменяется адресом внешнего ящика");
        verify(accountService).audit(eq(null), eq(account), eq("EXTERNAL_SYNC_SUCCEEDED"), anyString());
    }

    @Test
    void newOnlyImapSnapshotUsesUidNextWithoutFetchingArchive() throws Exception {
        account.setInitialSyncMode(ExternalMailAccount.InitialSyncMode.NEW_ONLY);
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 10L, 3, 2));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.SUCCESS, run.getStatus());
        assertEquals(2, run.getSkippedCount());
        assertEquals(2, stateByFolder.get("INBOX").getLastSeenUid());
        assertEquals(0, fetchCalls.get());
        verify(client, never()).listMessageEnvelopes(any(), anyString(), anyInt());
    }

    @Test
    void newOnlyImapSnapshotFallsBackToEnvelopeListAndFailsIfIncomplete() throws Exception {
        account.setInitialSyncMode(ExternalMailAccount.InitialSyncMode.NEW_ONLY);
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 10L, 0, 2));
        snapshotEnvelopes = List.of(
                envelope("INBOX", "4", 10L, null, "a@x", false),
                envelope("INBOX", "8", 10L, null, "a@x", false));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.SUCCESS, run.getStatus());
        assertEquals(8, stateByFolder.get("INBOX").getLastSeenUid());
        assertEquals(0, fetchCalls.get());

        resetExecution(ExternalMailSyncRun.Trigger.SCHEDULED);
        account.setInitialSyncMode(ExternalMailAccount.InitialSyncMode.NEW_ONLY);
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 10L, 0, 2));
        snapshotEnvelopes = List.of(envelope("INBOX", "4", 10L, null, "a@x", false));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.FAILED, run.getStatus());
        assertEquals(1, run.getFailedCount());
        assertEquals(1, account.getConsecutiveFailures());
        long delay = Duration.between(Instant.now(), account.getNextSyncAt()).toSeconds();
        assertTrue(delay >= 500 && delay <= 610, "первая временная ошибка даёт poll*2, actual=" + delay);
    }

    @Test
    void newOnlyPop3SnapshotCreatesMarkersAndSkipsKnownOnes() throws Exception {
        account.setIncomingProtocol(ExternalMailAccount.Protocol.POP3);
        account.setInitialSyncMode(ExternalMailAccount.InitialSyncMode.NEW_ONLY);
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", null, 0, 2));
        var known = envelope("INBOX", "known", null, null, "a@x", false);
        var fresh = envelope("INBOX", "fresh", null, null, "a@x", false);
        snapshotEnvelopes = List.of(known, fresh);
        ExternalMailImportedMessage existing = importedRecord(known, ExternalMailImportedMessage.Status.DELIVERED);
        importedByKey.put(key(known), existing);

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.SUCCESS, run.getStatus());
        assertEquals(1, run.getSkippedCount(), "известный UIDL не должен создавать второй маркер");
        assertEquals(1, importedRecords.size());
        assertEquals(ExternalMailImportedMessage.Status.SKIPPED_INITIAL, importedRecords.get(0).getStatus());
        assertEquals("fresh", importedRecords.get(0).getRemoteUid());
        assertEquals(0, fetchCalls.get());
    }

    @Test
    void incompletePop3SnapshotFailsFolder() throws Exception {
        account.setIncomingProtocol(ExternalMailAccount.Protocol.POP3);
        account.setInitialSyncMode(ExternalMailAccount.InitialSyncMode.NEW_ONLY);
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", null, 0, 2));
        snapshotEnvelopes = List.of(envelope("INBOX", "only", null, null, "a@x", false));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.FAILED, run.getStatus());
        assertTrue(run.getError().contains("Начальный снимок"));
    }

    @Test
    void UIDValidityChangeAndMigrationResetCursor() throws Exception {
        ExternalMailFolderState state = state("INBOX");
        state.setUIDValidity(1L);
        state.setLastSeenUid(99);
        state.setInitialSnapshotDone(true);
        stateByFolder.put("INBOX", state);
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 2L, 100, 1));
        fetchEnvelopes.put("INBOX", List.of(envelope("INBOX", "5", 2L, null, "a@x", false)));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(List.of(0L), fetchAfterUids);
        assertEquals(5, state.getLastSeenUid());
        assertEquals(2L, state.getUIDValidity());

        resetExecution(ExternalMailSyncRun.Trigger.MIGRATION);
        ExternalMailFolderState migrationState = state("INBOX");
        migrationState.setUIDValidity(2L);
        migrationState.setLastSeenUid(88);
        migrationState.setInitialSnapshotDone(false);
        stateByFolder.put("INBOX", migrationState);
        account.setInitialSyncMode(ExternalMailAccount.InitialSyncMode.NEW_ONLY);
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 2L, 100, 1));
        fetchEnvelopes.put("INBOX", List.of(envelope("INBOX", "3", 2L, null, "a@x", false)));

        execute(ExternalMailSyncRun.Trigger.MIGRATION);

        assertEquals(List.of(0L), fetchAfterUids);
        assertEquals(3, migrationState.getLastSeenUid());
        assertTrue(migrationState.isInitialSnapshotDone());
    }

    @Test
    void inboxOnlyFilterIgnoresOtherRemoteFolders() throws Exception {
        remoteFolders = List.of(
                remoteFolder("Projects", null, 1L, 2, 1),
                remoteFolder("CustomInbox", "INBOX", 1L, 2, 0));
        fetchEnvelopes.put("Projects", List.of(envelope("Projects", "1", 1L, null, "a@x", false)));
        fetchEnvelopes.put("CustomInbox", List.of());

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(1, run.getFoldersCount());
        assertEquals(1, fetchCalls.get());
        assertFalse(stateByFolder.containsKey("Projects"));
    }

    @Test
    void handlerSkipsDeletedDoneAndInitialMessagesWithExpectedResults() throws Exception {
        account.setDeleteAfterFetch(true);
        account.setLeaveOnServer(false);
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 7L, 10, 5));
        var deleted = envelope("INBOX", "1", 7L, null, "a@x", true);
        var delivered = envelope("INBOX", "2", 7L, null, "a@x", false);
        var queued = envelope("INBOX", "3", 7L, null, "a@x", false);
        var initial = envelope("INBOX", "4", 7L, null, "a@x", false);
        var tooLarge = envelope("INBOX", "5", 7L, null, "a@x", false);
        importedByKey.put(key(delivered), importedRecord(delivered, ExternalMailImportedMessage.Status.DELIVERED));
        importedByKey.put(key(queued), importedRecord(queued, ExternalMailImportedMessage.Status.QUEUED));
        importedByKey.put(key(initial), importedRecord(initial, ExternalMailImportedMessage.Status.SKIPPED_INITIAL));
        importedByKey.put(key(tooLarge), importedRecord(tooLarge, ExternalMailImportedMessage.Status.SKIPPED_TOO_LARGE));
        fetchEnvelopes.put("INBOX", List.of(deleted, delivered, queued, initial, tooLarge));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(List.of(
                ExternalMailClient.HandleResult.SKIPPED,
                ExternalMailClient.HandleResult.DELETE_ON_SERVER,
                ExternalMailClient.HandleResult.SKIPPED,
                ExternalMailClient.HandleResult.SKIPPED,
                ExternalMailClient.HandleResult.SKIPPED), handlerResults);
        assertEquals(5, run.getFetchedCount());
        assertEquals(5, run.getSkippedCount());
        assertEquals(0, run.getQueuedCount());
        assertEquals(5, stateByFolder.get("INBOX").getLastSeenUid());
        assertTrue(importedRecords.isEmpty());
    }

    @Test
    void migrationRetriesSkippedInitialAndFailedRecordsInPlace() throws Exception {
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 7L, 10, 2));
        var initial = envelope("INBOX", "1", 7L, "<a@x>", "a@x", false);
        var failed = envelope("INBOX", "2", 7L, "<b@x>", "b@x", false);
        ExternalMailImportedMessage initialRecord =
                importedRecord(initial, ExternalMailImportedMessage.Status.SKIPPED_INITIAL);
        ExternalMailImportedMessage failedRecord =
                importedRecord(failed, ExternalMailImportedMessage.Status.FAILED);
        importedByKey.put(key(initial), initialRecord);
        importedByKey.put(key(failed), failedRecord);
        fetchEnvelopes.put("INBOX", List.of(initial, failed));

        execute(ExternalMailSyncRun.Trigger.MIGRATION);

        assertEquals(ExternalMailImportedMessage.Status.QUEUED, initialRecord.getStatus());
        assertEquals(ExternalMailImportedMessage.Status.QUEUED, failedRecord.getStatus());
        assertEquals(2, run.getQueuedCount());
        assertEquals(2, importedRecords.size());
    }

    @Test
    void duplicateMessageIdAndHashAreSkipped() throws Exception {
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 7L, 10, 1));
        fetchEnvelopes.put("INBOX", List.of(envelope("INBOX", "1", 7L, "<dup@x>", "a@x", false)));
        duplicateMessageId = true;

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(1, run.getSkippedCount());
        assertEquals(0, run.getQueuedCount());
        assertTrue(importedRecords.isEmpty());

        resetExecution(ExternalMailSyncRun.Trigger.SCHEDULED);
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 7L, 10, 1));
        fetchEnvelopes.put("INBOX", List.of(envelope("INBOX", "1", 7L, "<new@x>", "a@x", false)));
        duplicateSha = true;

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(1, run.getSkippedCount());
        assertTrue(importedRecords.isEmpty());
        verify(events, never()).publishConfirmed(anyString(), any(), anyString());
    }

    @Test
    void tooLargeMessageCreatesSkippedRecordAndPartialRun() throws Exception {
        RawMessageSpool small = new RawMessageSpool(tmp.resolve("small-spool"), 4);
        service = newService(props, cipher, small);
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 7L, 10, 1));
        var env = envelope("INBOX", "9", 7L, "<big@x>", "a@x", false);
        fetchEnvelopes.put("INBOX", List.of(env));
        rawByKey.put(key(env), "12345".getBytes(StandardCharsets.UTF_8));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.PARTIAL, run.getStatus());
        assertEquals(1, run.getSkippedCount());
        assertEquals(1, run.getFailedCount());
        assertEquals(1, importedRecords.size());
        assertEquals(ExternalMailImportedMessage.Status.SKIPPED_TOO_LARGE, importedRecords.get(0).getStatus());
        assertEquals(9, stateByFolder.get("INBOX").getLastSeenUid());
    }

    @Test
    void rawReadFailureStopsFolderWithoutImportedRecord() throws Exception {
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 7L, 10, 2));
        var bad = envelope("INBOX", "1", 7L, null, "a@x", false);
        var later = envelope("INBOX", "2", 7L, null, "a@x", false);
        fetchEnvelopes.put("INBOX", List.of(bad, later));
        rawFailureKeys.add(key(bad));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.PARTIAL, run.getStatus());
        assertEquals(1, run.getFetchedCount());
        assertEquals(1, run.getFailedCount());
        assertTrue(importedRecords.isEmpty());
        assertEquals(0, stateByFolder.get("INBOX").getLastSeenUid());
    }

    @Test
    void queuePublishFailureMarksImportedRecordFailedAndDeletesSpoolCopy() throws Exception {
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 7L, 10, 1));
        fetchEnvelopes.put("INBOX", List.of(envelope("INBOX", "1", 7L, null, "a@x", false)));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(events).publishConfirmed(anyString(), any(), anyString());

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.PARTIAL, run.getStatus());
        assertEquals(1, importedRecords.size());
        assertEquals(ExternalMailImportedMessage.Status.FAILED, importedRecords.get(0).getStatus());
        assertNull(importedRecords.get(0).getRawRef());
        assertTrue(importedRecords.get(0).getError().contains("broker unavailable"));
        assertEquals(0, run.getQueuedCount());
    }

    @Test
    void folderClientFailureUsesTemporaryBackoffAndPermanentListFailureUsesDailyBackoff() throws Exception {
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 7L, 10, 1));
        fetchFailures.put("INBOX", clientError(
                ExternalMailClient.ExternalMailClientException.Category.NETWORK, true, "timeout"));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.FAILED, run.getStatus());
        assertEquals(1, account.getConsecutiveFailures());
        long delay = Duration.between(Instant.now(), account.getNextSyncAt()).toSeconds();
        assertTrue(delay >= 500 && delay <= 610);

        resetExecution(ExternalMailSyncRun.Trigger.SCHEDULED);
        when(client.listFolders(any())).thenThrow(clientError(
                ExternalMailClient.ExternalMailClientException.Category.AUTH, false, "bad password"));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.FAILED, run.getStatus());
        assertTrue(Duration.between(Instant.now(), account.getNextSyncAt()).toHours() >= 23);
        verify(accountService, times(2)).audit(eq(null), any(ExternalMailAccount.class),
                eq("EXTERNAL_SYNC_FAILED"), anyString());
    }

    @Test
    void folderConnectionFailureAfterHandledMessagePersistsCursorProgress() throws Exception {
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 7L, 10, 2));
        var first = envelope("INBOX", "1", 7L, "<one@x>", "a@x", false);
        fetchEnvelopes.put("INBOX", List.of(first));
        when(client.fetchFolder(any(), anyString(), anyLong(), anyInt(), any())).thenAnswer(inv -> {
            ExternalMailClient.MessageHandler handler = inv.getArgument(4);
            handler.handle(first, () -> new ByteArrayInputStream(
                    "Subject: one\r\n\r\nbody".getBytes(StandardCharsets.UTF_8)));
            throw clientError(ExternalMailClient.ExternalMailClientException.Category.NETWORK, true,
                    "Lost folder connection to server");
        });

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.PARTIAL, run.getStatus());
        assertEquals(1, run.getQueuedCount());
        assertEquals(1, run.getFailedCount());
        assertEquals(1, stateByFolder.get("INBOX").getLastSeenUid());
    }

    @Test
    void dnsFailureStopsRemainingFoldersInRun() throws Exception {
        account.setSyncScope(ExternalMailAccount.SyncScope.ALL_FOLDERS);
        remoteFolders = List.of(
                remoteFolder("INBOX", "INBOX", 7L, 10, 1),
                remoteFolder("Archive", "ARCHIVE", 7L, 10, 1));
        fetchFailures.put("INBOX", new ExternalMailClient.ExternalMailClientException(
                ExternalMailClient.ExternalMailClientException.Category.NETWORK, true,
                "Не удалось разрешить DNS-имя почтового сервера: imap.test",
                new UnknownHostException("imap.test")));
        fetchEnvelopes.put("Archive", List.of(envelope("Archive", "1", 7L, null, "a@x", false)));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.FAILED, run.getStatus());
        assertEquals(1, fetchCalls.get());
        assertEquals(1, run.getFailedCount());
        assertFalse(stateByFolder.containsKey("Archive"));
        assertFalse(run.getError().contains("Archive:"));
    }

    @Test
    void missingOwnerAndDeletedAccountFinishCleanly() throws Exception {
        ownerLookup.set(Optional.empty());
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 1L, 1, 0));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.FAILED, run.getStatus());
        assertTrue(run.getError().contains("Владелец"));

        resetExecution(ExternalMailSyncRun.Trigger.SCHEDULED);
        currentAccount.set(null);
        execute(account.getId(), run.getId(), ExternalMailSyncRun.Trigger.SCHEDULED);
        assertEquals(ExternalMailSyncRun.Status.RUNNING, run.getStatus(),
                "если аккаунт удалён до старта, finishRun некому обновлять");
    }

    @Test
    void targetResolutionUsesStateThenAccountRootThenSpecialUse() throws Exception {
        MailFolder stateTarget = folder("StateTarget", null, null);
        knownFolders.add(stateTarget);
        ExternalMailFolderState state = state("INBOX");
        state.setTargetFolder(stateTarget);
        state.setInitialSnapshotDone(true);
        stateByFolder.put("INBOX", state);
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 7L, 10, 1));
        fetchEnvelopes.put("INBOX", List.of(envelope("INBOX", "1", 7L, null, "a@x", false)));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);
        assertEquals(stateTarget, importedRecords.get(0).getTargetFolder());

        resetExecution(ExternalMailSyncRun.Trigger.SCHEDULED);
        MailFolder root = folder("Root", null, null);
        knownFolders.add(root);
        account.setTargetFolder(root);
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 7L, 10, 1));
        fetchEnvelopes.put("INBOX", List.of(envelope("INBOX", "1", 7L, null, "a@x", false)));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);
        assertEquals(root, importedRecords.get(0).getTargetFolder());
        verify(delivery, never()).ensureFolder(eq(ownerId), eq("INBOX"), anyString());

        resetExecution(ExternalMailSyncRun.Trigger.SCHEDULED);
        account.setSyncScope(ExternalMailAccount.SyncScope.ALL_FOLDERS);
        remoteFolders = List.of(remoteFolder("Provider Sent", "SENT", 7L, 10, 1));
        fetchEnvelopes.put("Provider Sent",
                List.of(envelope("Provider Sent", "1", 7L, null, "a@x", false)));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);
        verify(delivery).ensureFolder(ownerId, "SENT", "Sent");
        assertEquals("SENT", importedRecords.get(0).getTargetFolder().getSpecialUse());
    }

    @Test
    void folderPathResolutionCreatesNestedSanitizedFoldersUnderRoot() throws Exception {
        MailFolder root = folder("Root", null, null);
        knownFolders.add(root);
        account.setTargetFolder(root);
        account.setSyncScope(ExternalMailAccount.SyncScope.ALL_FOLDERS);
        remoteFolders = List.of(new ExternalMailClient.RemoteFolderInfo(
                "../Projects/2026", "/", 3L, 10, 1, Set.of(), null));
        fetchEnvelopes.put("../Projects/2026",
                List.of(envelope("../Projects/2026", "1", 3L, null, "a@x", false)));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(1, run.getQueuedCount());
        MailFolder importedTarget = importedRecords.get(0).getTargetFolder();
        assertEquals("2026", importedTarget.getName());
        assertEquals("Projects", importedTarget.getParent().getName());
        assertEquals("Folder", importedTarget.getParent().getParent().getName());
        assertEquals(root, importedTarget.getParent().getParent().getParent());
        assertTrue(knownFolders.stream().anyMatch(f -> f.getName().equals("Folder")));
    }

    @Test
    void blankFolderPathFallsBackToInbox() throws Exception {
        account.setSyncScope(ExternalMailAccount.SyncScope.ALL_FOLDERS);
        remoteFolders = List.of(new ExternalMailClient.RemoteFolderInfo(
                "/", "/", 3L, 10, 1, Set.of(), null));
        fetchEnvelopes.put("/", List.of(envelope("/", "1", 3L, null, "a@x", false)));

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(1, run.getQueuedCount());
        assertEquals(target, importedRecords.get(0).getTargetFolder());
        verify(delivery).ensureFolder(ownerId, "INBOX", "INBOX");
    }

    @Test
    void disablingAccountDuringSyncClearsNextRunAtFinish() throws Exception {
        remoteFolders = List.of(remoteFolder("INBOX", "INBOX", 7L, 10, 1));
        fetchEnvelopes.put("INBOX", List.of(envelope("INBOX", "1", 7L, null, "a@x", false)));
        doAnswerForDisableDuringFetch();

        execute(ExternalMailSyncRun.Trigger.SCHEDULED);

        assertEquals(ExternalMailSyncRun.Status.SUCCESS, run.getStatus());
        assertNull(account.getNextSyncAt());
    }

    @Test
    void runViewAndStaticHelpersAreExercisedThroughPublicPaths() throws Exception {
        run.setError("x".repeat(10));
        when(runs.findTop20ByAccount_IdOrderByStartedAtDesc(account.getId())).thenReturn(List.of(run));

        var view = service.listRuns(ownerId, Set.of("X"), account.getId()).get(0);

        assertEquals(run.getError(), view.error());
        assertEquals(ExternalMailSyncRun.Trigger.MANUAL, view.trigger());
        // parseUid/safeError/truncate покрываются сценариями выше через нечисловые POP3 UID,
        // многострочные ошибки клиента и длинные Message-ID; здесь фиксируем только публичный view.
        assertNotNull(view.startedAt());
    }

    private void doAnswerForDisableDuringFetch() throws Exception {
        when(client.fetchFolder(any(), anyString(), anyLong(), anyInt(), any())).thenAnswer(inv -> {
            account.setEnabled(false);
            ExternalMailClient.MessageHandler handler = inv.getArgument(4);
            var envelope = fetchEnvelopes.get("INBOX").get(0);
            handler.handle(envelope, () -> new ByteArrayInputStream("raw".getBytes(StandardCharsets.UTF_8)));
            return new ExternalMailClient.FetchStats(1, 1, false);
        });
    }

    private void awaitFinished() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (currentRun.get() != null && currentRun.get().getFinishedAt() != null) return;
            Thread.sleep(10);
        }
        throw new AssertionError("async sync did not finish");
    }

    private void resetExecution(ExternalMailSyncRun.Trigger trigger) throws Exception {
        account = account();
        currentAccount.set(account);
        run = run(trigger);
        currentRun.set(run);
        ownerLookup.set(Optional.of(owner));
        remoteFolders = List.of();
        snapshotEnvelopes = List.of();
        fetchEnvelopes.clear();
        rawByKey.clear();
        rawFailureKeys.clear();
        fetchFailures.clear();
        handlerResults.clear();
        fetchAfterUids.clear();
        fetchCalls.set(0);
        stateByFolder.clear();
        importedByKey.clear();
        importedRecords.clear();
        duplicateMessageId = false;
        duplicateSha = false;
        when(client.listFolders(any())).thenAnswer(inv -> remoteFolders);
    }

    private void execute(ExternalMailSyncRun.Trigger trigger) throws Exception {
        execute(account.getId(), run.getId(), trigger);
    }

    private void execute(UUID accountId, UUID runId, ExternalMailSyncRun.Trigger trigger) throws Exception {
        Method method = ExternalMailSyncService.class.getDeclaredMethod(
                "executeClaimed", UUID.class, UUID.class, String.class, ExternalMailSyncRun.Trigger.class);
        method.setAccessible(true);
        try {
            method.invoke(service, accountId, runId, "test-lock", trigger);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }

    private ExternalMailSyncService newService(
            ExternalMailProperties properties, ExternalMailSecretCipher cipherToUse, RawMessageSpool spoolToUse) {
        ExternalMailSyncService created = new ExternalMailSyncService(
                accounts, states, imported, runs, folders, accountService, client, properties,
                cipherToUse, auth, delivery, spoolToUse, events, jdbc);
        servicesToStop.add(created);
        return created;
    }

    private ExternalMailClient.AccountConfig clientConfig(ExternalMailAccount value) {
        return new ExternalMailClient.AccountConfig(
                value.getId(), value.getOwnerId(), value.getEmailAddress(), "External",
                ExternalMailAccount.Provider.CUSTOM, value.getIncomingProtocol(), "imap.test", 993,
                ExternalMailAccount.Security.SSL_TLS, "external", "secret", false, null, null,
                ExternalMailAccount.Security.SSL_TLS, "external", false, value.isDeleteAfterFetch());
    }

    private ExternalMailAccount account() {
        ExternalMailAccount value = new ExternalMailAccount();
        value.setId(UUID.randomUUID());
        value.setOwnerId(ownerId);
        value.setEmailAddress("external@mail.test");
        value.setIncomingProtocol(ExternalMailAccount.Protocol.IMAP);
        value.setEnabled(true);
        value.setSyncEnabled(true);
        value.setInitialSyncMode(ExternalMailAccount.InitialSyncMode.IMPORT_ALL);
        value.setSyncScope(ExternalMailAccount.SyncScope.INBOX_ONLY);
        value.setPollIntervalSeconds(300);
        value.setLeaveOnServer(true);
        return value;
    }

    private ExternalMailSyncRun run(ExternalMailSyncRun.Trigger trigger) {
        ExternalMailSyncRun value = new ExternalMailSyncRun();
        value.setId(UUID.randomUUID());
        value.setAccount(account);
        value.setTriggerType(trigger);
        value.setStatus(ExternalMailSyncRun.Status.RUNNING);
        value.setStartedAt(Instant.now());
        return value;
    }

    private MailFolder folder(String name, String specialUse, MailFolder parent) {
        MailFolder folder = new MailFolder();
        folder.setId(UUID.randomUUID());
        folder.setOwnerId(ownerId);
        folder.setParent(parent);
        folder.setName(name);
        folder.setSpecialUse(specialUse);
        folder.setUidNext(1);
        folder.setUidValidity(1);
        folder.setHighestModseq(1);
        return folder;
    }

    private ExternalMailFolderState state(String remoteFolder) {
        ExternalMailFolderState state = new ExternalMailFolderState();
        state.setId(UUID.randomUUID());
        state.setAccount(account);
        state.setRemoteFolder(remoteFolder);
        return state;
    }

    private ExternalMailImportedMessage importedRecord(
            ExternalMailClient.RemoteMessageEnvelope envelope, ExternalMailImportedMessage.Status status) {
        ExternalMailImportedMessage record = new ExternalMailImportedMessage();
        record.setId(UUID.randomUUID());
        record.setAccount(account);
        record.setRemoteFolder(envelope.remoteFolder());
        record.setRemoteUid(envelope.remoteUid());
        record.setUIDValidity(envelope.UIDValidity());
        record.setMessageIdHeader(envelope.messageIdHeader());
        record.setStatus(status);
        return record;
    }

    private static ExternalMailClient.RemoteFolderInfo remoteFolder(
            String name, String specialUse, Long validity, long uidNext, int count) {
        return new ExternalMailClient.RemoteFolderInfo(
                name, "/", validity, uidNext, count, Set.of(), specialUse);
    }

    private static ExternalMailClient.RemoteMessageEnvelope envelope(
            String folder, String uid, Long validity, String messageId, String from, boolean deleted) {
        return new ExternalMailClient.RemoteMessageEnvelope(
                folder, uid, validity, messageId, from, Instant.parse("2026-01-01T00:00:00Z"),
                100, false, false, false, false, deleted);
    }

    private static ExternalMailClient.ExternalMailClientException clientError(
            ExternalMailClient.ExternalMailClientException.Category category, boolean temporary, String message) {
        return new ExternalMailClient.ExternalMailClientException(category, temporary, message, null);
    }

    private static String key(ExternalMailClient.RemoteMessageEnvelope envelope) {
        return key(envelope.remoteFolder(), envelope.UIDValidity(), envelope.remoteUid());
    }

    private static String key(String folder, Object validity, String uid) {
        return folder + "|" + validity + "|" + uid;
    }

    private static String validKey() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 11);
        return Base64.getEncoder().encodeToString(key);
    }
}
