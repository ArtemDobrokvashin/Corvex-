package ru.corvex.mailstorage.external;

import jakarta.annotation.PreDestroy;
import jakarta.mail.FolderClosedException;
import jakarta.mail.StoreClosedException;
import org.eclipse.angus.mail.iap.ConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.ExternalMailDtos;
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

import java.io.InputStream;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Планировщик и worker сбора внешних ящиков. Собранное письмо не пишется напрямую в Maildir:
 * оно ставится в существующий inbound-конвейер spam → antivirus → storage/search, а запись
 * external_mail_imported_messages сообщает финальной доставке папку/флаги/дату источника.
 */
@Service
public class ExternalMailSyncService {

    private static final Logger log = LoggerFactory.getLogger(ExternalMailSyncService.class);
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);
    private static final int POP3_SNAPSHOT_LIMIT = 5000;
    private static final int MAX_RECONNECTS_PER_RUN = 2;
    private static final Set<ExternalMailImportedMessage.Status> DONE_STATUSES = Set.of(
            ExternalMailImportedMessage.Status.QUEUED,
            ExternalMailImportedMessage.Status.DELIVERED,
            ExternalMailImportedMessage.Status.SKIPPED_TOO_LARGE);

    private final ExternalMailAccountRepository accounts;
    private final ExternalMailFolderStateRepository states;
    private final ExternalMailImportedMessageRepository imported;
    private final ExternalMailSyncRunRepository runs;
    private final MailFolderRepository folders;
    private final ExternalMailAccountService accountService;
    private final ExternalMailClient client;
    private final ExternalMailProperties props;
    private final ExternalMailSecretCipher cipher;
    private final AuthUserClient auth;
    private final MailDeliveryService delivery;
    private final RawMessageSpool spool;
    private final MailEventPublisher events;
    private final JdbcTemplate jdbc;
    private final ExecutorService executor;
    private final String instanceId = UUID.randomUUID().toString();

    public ExternalMailSyncService(ExternalMailAccountRepository accounts,
                                   ExternalMailFolderStateRepository states,
                                   ExternalMailImportedMessageRepository imported,
                                   ExternalMailSyncRunRepository runs,
                                   MailFolderRepository folders,
                                   ExternalMailAccountService accountService,
                                   ExternalMailClient client,
                                   ExternalMailProperties props,
                                   ExternalMailSecretCipher cipher,
                                   AuthUserClient auth,
                                   MailDeliveryService delivery,
                                   RawMessageSpool spool,
                                   MailEventPublisher events,
                                   JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.states = states;
        this.imported = imported;
        this.runs = runs;
        this.folders = folders;
        this.accountService = accountService;
        this.client = client;
        this.props = props;
        this.cipher = cipher;
        this.auth = auth;
        this.delivery = delivery;
        this.spool = spool;
        this.events = events;
        this.jdbc = jdbc;
        this.executor = Executors.newFixedThreadPool(props.safeSchedulerThreads(), runnable -> {
            Thread thread = new Thread(runnable, "corvex-external-mail-sync");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${corvex.external-mail.scheduler-delay-ms:30000}")
    public void pollDue() {
        if (!props.enabled() || !cipher.configured()) return;
        List<ExternalMailAccount> due;
        try {
            due = accounts.findDue(Instant.now(), PageRequest.of(0, 50));
        } catch (RuntimeException e) {
            log.warn("External mail scheduler не смог прочитать очередь аккаунтов: {}", e.getMessage());
            return;
        }
        for (ExternalMailAccount account : due) {
            startSync(account.getId(), ExternalMailSyncRun.Trigger.SCHEDULED);
        }
    }

    public ExternalMailDtos.SyncRunView requestSync(UUID actorId, Set<String> authorities, UUID accountId,
                                                    ExternalMailSyncRun.Trigger trigger) {
        ExternalMailAccount account = accountService.accessibleAccount(actorId, authorities, accountId);
        if (!account.isEnabled() || !account.isSyncEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сбор этого внешнего ящика отключён");
        }
        ExternalMailSyncRun run = startSync(accountId, trigger)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Синхронизация этого внешнего ящика уже выполняется"));
        accountService.audit(actorId, account, trigger == ExternalMailSyncRun.Trigger.MIGRATION
                ? "EXTERNAL_MIGRATION_REQUESTED" : "EXTERNAL_SYNC_REQUESTED", "runId=" + run.getId());
        return toView(run);
    }

    public List<ExternalMailDtos.SyncRunView> listRuns(UUID actorId, Set<String> authorities, UUID accountId) {
        accountService.accessibleAccount(actorId, authorities, accountId);
        return runs.findTop20ByAccount_IdOrderByStartedAtDesc(accountId).stream().map(this::toView).toList();
    }

    public Optional<ExternalMailSyncRun> startSync(UUID accountId, ExternalMailSyncRun.Trigger trigger) {
        String lockOwner = instanceId + ":" + UUID.randomUUID();
        Instant now = Instant.now();
        int claimed = jdbc.update("""
                        update external_mail_accounts
                           set sync_lock_owner = ?, sync_lock_until = ?, updated_at = ?
                         where id = ? and enabled = true and sync_enabled = true
                           and (sync_lock_until is null or sync_lock_until < ?)
                        """,
                lockOwner, Timestamp.from(now.plus(LOCK_TTL)), Timestamp.from(now), accountId, Timestamp.from(now));
        if (claimed != 1) return Optional.empty();
        ExternalMailAccount account = accounts.findById(accountId).orElse(null);
        if (account == null) {
            releaseClaim(accountId, lockOwner);
            return Optional.empty();
        }
        ExternalMailSyncRun run = new ExternalMailSyncRun();
        run.setAccount(account);
        run.setTriggerType(trigger);
        run.setStatus(ExternalMailSyncRun.Status.RUNNING);
        run.setStartedAt(now);
        run = runs.saveAndFlush(run);
        UUID runId = run.getId();
        try {
            executor.submit(() -> executeClaimed(accountId, runId, lockOwner, trigger));
        } catch (RejectedExecutionException e) {
            run.setStatus(ExternalMailSyncRun.Status.FAILED);
            run.setFinishedAt(Instant.now());
            run.setError("Очередь синхронизации переполнена");
            runs.saveAndFlush(run);
            releaseClaim(accountId, lockOwner);
            return Optional.empty();
        }
        return Optional.of(run);
    }

    // ------------------------------------------------------------------ execution

    private static final class Counters {
        final AtomicInteger fetched = new AtomicInteger();
        final AtomicInteger queued = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final List<String> errors = new ArrayList<>();
        volatile ExternalMailClient.ExternalMailClientException.Category permanentCategory;
        int foldersDone;
    }

    private void executeClaimed(UUID accountId, UUID runId, String lockOwner, ExternalMailSyncRun.Trigger trigger) {
        Counters counters = new Counters();
        ExternalMailAccount account = null;
        boolean migration = trigger == ExternalMailSyncRun.Trigger.MIGRATION;
        try {
            account = accounts.findById(accountId)
                    .orElseThrow(() -> new IllegalStateException("Внешний ящик удалён до начала синхронизации"));
            account.setLastSyncAt(Instant.now());
            account = accounts.saveAndFlush(account);

            AuthUserClient.MailboxOwner owner = auth.findById(account.getOwnerId())
                    .filter(AuthUserClient.MailboxOwner::active)
                    .orElseThrow(() -> new IllegalStateException("Владелец ящика Corvex не найден или неактивен"));
            ExternalMailClient.AccountConfig config = accountService.toConfig(account);
            boolean syncAllFolders = migration
                    || account.getSyncScope() == ExternalMailAccount.SyncScope.ALL_FOLDERS;
            ExternalMailClient.IncomingSession incoming = client.openIncoming(config);
            try {
                List<ExternalMailClient.RemoteFolderInfo> remoteFolders = incoming.listFolders().stream()
                        .filter(f -> syncAllFolders
                                || "INBOX".equalsIgnoreCase(f.specialUse()) || "INBOX".equalsIgnoreCase(f.name()))
                        .limit(props.safeMaxFolders())
                        .toList();
                updateRun(runId, counters, remoteFolders.size(), null);

                boolean stopRun = false;
                int reconnects = 0;
                for (ExternalMailClient.RemoteFolderInfo remoteFolder : remoteFolders) {
                    while (!stopRun) {
                        boolean retry = false;
                        try {
                            syncFolder(account, owner, config, incoming, remoteFolder, counters, migration);
                            counters.foldersDone++;
                        } catch (ExternalMailClient.ExternalMailClientException e) {
                            if (isConnectionLoss(e) && reconnects < MAX_RECONNECTS_PER_RUN) {
                                reconnects++;
                                log.warn("External mail sync connection lost; reconnecting account={} run={} folder={} attempt={}/{}",
                                        accountId, runId, remoteFolder.name(), reconnects, MAX_RECONNECTS_PER_RUN);
                                try {
                                    incoming.close();
                                    incoming = client.openIncoming(config);
                                    retry = true;
                                } catch (ExternalMailClient.ExternalMailClientException reconnectError) {
                                    recordFolderFailure(accountId, runId, remoteFolder.name(), counters, reconnectError);
                                    stopRun = true;
                                } catch (RuntimeException reconnectError) {
                                    counters.failed.incrementAndGet();
                                    counters.errors.add(remoteFolder.name() + ": " + safeError(reconnectError));
                                    stopRun = true;
                                }
                            } else {
                                recordFolderFailure(accountId, runId, remoteFolder.name(), counters, e);
                                if (isAccountLevelFailure(e)) stopRun = true;
                            }
                        } catch (RuntimeException e) {
                            counters.failed.incrementAndGet();
                            counters.errors.add(remoteFolder.name() + ": " + safeError(e));
                        } finally {
                            heartbeat(accountId, lockOwner);
                            updateRun(runId, counters, remoteFolders.size(), String.join("; ", counters.errors));
                        }
                        if (!retry) break;
                    }
                    if (stopRun) break;
                }
                finishRun(account, runId, counters, remoteFolders.size());
            } finally {
                incoming.close();
            }
        } catch (ExternalMailClient.ExternalMailClientException e) {
            counters.failed.incrementAndGet();
            counters.errors.add(e.category() + ": " + e.getMessage());
            if (!e.temporary()) counters.permanentCategory = e.category();
            if (account != null) finishRun(account, runId, counters, 0);
        } catch (Exception e) {
            counters.failed.incrementAndGet();
            counters.errors.add(safeError(e));
            log.warn("External mail sync failed account={} run={}: {}", accountId, runId, safeError(e));
            if (account != null) finishRun(account, runId, counters, 0);
        } finally {
            releaseClaim(accountId, lockOwner);
        }
    }

    private void recordFolderFailure(UUID accountId, UUID runId, String remoteFolder, Counters counters,
                                     ExternalMailClient.ExternalMailClientException e) {
        counters.failed.incrementAndGet();
        counters.errors.add(remoteFolder + ": " + e.category() + ": " + e.getMessage());
        if (!e.temporary()) counters.permanentCategory = e.category();
        log.warn("External mail sync folder failed account={} run={} folder={} category={} temporary={}: {}",
                accountId, runId, remoteFolder, e.category(), e.temporary(), e.getMessage(), e);
    }

    private void syncFolder(ExternalMailAccount account,
                            AuthUserClient.MailboxOwner owner,
                            ExternalMailClient.AccountConfig config,
                            ExternalMailClient.IncomingSession incoming,
                            ExternalMailClient.RemoteFolderInfo remoteFolder,
                            Counters counters,
                            boolean migration) throws ExternalMailClient.ExternalMailClientException {
        ExternalMailFolderState state = states.findByAccount_IdAndRemoteFolder(account.getId(), remoteFolder.name())
                .orElseGet(() -> {
                    ExternalMailFolderState created = new ExternalMailFolderState();
                    created.setAccount(account);
                    created.setRemoteFolder(remoteFolder.name());
                    created.setLastSeenUid(0);
                    created.setInitialSnapshotDone(false);
                    return states.saveAndFlush(created);
                });
        state.setRemoteDelimiter(remoteFolder.delimiter());
        if (remoteFolder.UIDValidity() != null) {
            if (state.getUIDValidity() != null && !state.getUIDValidity().equals(remoteFolder.UIDValidity())) {
                state.setLastSeenUid(0);
            }
            state.setUIDValidity(remoteFolder.UIDValidity());
        }
        MailFolder target = resolveTargetFolder(account, owner, remoteFolder, state, migration);
        state.setTargetFolder(target);
        if (migration) {
            state.setLastSeenUid(0);
            state.setInitialSnapshotDone(true);
        }
        state = states.saveAndFlush(state);

        if (!state.isInitialSnapshotDone()
                && account.getInitialSyncMode() == ExternalMailAccount.InitialSyncMode.NEW_ONLY) {
            boolean done = snapshotExisting(account, config, incoming, remoteFolder, state, counters);
            state.setInitialSnapshotDone(done);
            state.setLastSyncAt(Instant.now());
            states.saveAndFlush(state);
            if (!done) {
                throw new ExternalMailClient.ExternalMailClientException(
                        ExternalMailClient.ExternalMailClientException.Category.PROTOCOL, true,
                        "Начальный снимок POP3/IMAP папки больше одного пакета; выберите IMAP или импорт архива", null);
            }
            return;
        }
        if (!state.isInitialSnapshotDone()) {
            state.setInitialSnapshotDone(true);
            state = states.saveAndFlush(state);
        }

        AtomicLong lastSuccessfulUid = new AtomicLong(state.getLastSeenUid());
        long afterUid = account.getIncomingProtocol() == ExternalMailAccount.Protocol.IMAP ? state.getLastSeenUid() : 0;
        try {
            incoming.fetchFolder(remoteFolder.name(), afterUid, props.safeBatchSize(),
                    (envelope, source) -> handleMessage(account, owner, remoteFolder, target, envelope, source,
                            counters, lastSuccessfulUid, migration));
        } finally {
            // Даже при обрыве соединения уже успешно обработанные UID нельзя терять:
            // иначе следующий запуск снова пойдёт по тому же тяжёлому диапазону.
            state.setLastSeenUid(lastSuccessfulUid.get());
            state.setLastSyncAt(Instant.now());
            states.saveAndFlush(state);
        }
    }

    private boolean snapshotExisting(ExternalMailAccount account,
                                     ExternalMailClient.AccountConfig config,
                                     ExternalMailClient.IncomingSession incoming,
                                     ExternalMailClient.RemoteFolderInfo remoteFolder,
                                     ExternalMailFolderState state,
                                     Counters counters) throws ExternalMailClient.ExternalMailClientException {
        if (account.getIncomingProtocol() == ExternalMailAccount.Protocol.IMAP) {
            long cursor = Math.max(0, remoteFolder.uidNext() - 1);
            if (cursor == 0 && remoteFolder.messageCount() > 0) {
                List<ExternalMailClient.RemoteMessageEnvelope> envelopes =
                        incoming.listMessageEnvelopes(remoteFolder.name(), props.safeBatchSize());
                if (envelopes.size() < remoteFolder.messageCount()) return false;
                cursor = envelopes.stream().mapToLong(e -> parseUid(e.remoteUid())).max().orElse(0);
            }
            state.setLastSeenUid(cursor);
            counters.skipped.addAndGet(remoteFolder.messageCount());
            return true;
        }
        List<ExternalMailClient.RemoteMessageEnvelope> envelopes =
                incoming.listMessageEnvelopes(remoteFolder.name(), POP3_SNAPSHOT_LIMIT);
        if (envelopes.size() < remoteFolder.messageCount()) return false;
        for (ExternalMailClient.RemoteMessageEnvelope envelope : envelopes) {
            if (imported.findRemote(account.getId(), envelope.remoteFolder(), envelope.UIDValidity(), envelope.remoteUid()).isPresent()) {
                continue;
            }
            ExternalMailImportedMessage marker = baseImported(account, envelope, state.getTargetFolder(), false);
            marker.setStatus(ExternalMailImportedMessage.Status.SKIPPED_INITIAL);
            imported.saveAndFlush(marker);
            counters.skipped.incrementAndGet();
        }
        return true;
    }

    private ExternalMailClient.HandleResult handleMessage(ExternalMailAccount account,
                                                          AuthUserClient.MailboxOwner owner,
                                                          ExternalMailClient.RemoteFolderInfo remoteFolder,
                                                          MailFolder target,
                                                          ExternalMailClient.RemoteMessageEnvelope envelope,
                                                          ExternalMailClient.RawMessageSource source,
                                                          Counters counters,
                                                          AtomicLong lastSuccessfulUid,
                                                          boolean migration) {
        counters.fetched.incrementAndGet();
        if (envelope.deleted()) {
            counters.skipped.incrementAndGet();
            advanceCursor(account, envelope, lastSuccessfulUid);
            return ExternalMailClient.HandleResult.SKIPPED;
        }
        Optional<ExternalMailImportedMessage> existingOpt = imported.findRemote(
                account.getId(), envelope.remoteFolder(), envelope.UIDValidity(), envelope.remoteUid());
        ExternalMailImportedMessage retryRecord = null;
        if (existingOpt.isPresent()) {
            ExternalMailImportedMessage existing = existingOpt.get();
            if (existing.getStatus() == ExternalMailImportedMessage.Status.DELIVERED && account.isDeleteAfterFetch()) {
                counters.skipped.incrementAndGet();
                advanceCursor(account, envelope, lastSuccessfulUid);
                return ExternalMailClient.HandleResult.DELETE_ON_SERVER;
            }
            if (DONE_STATUSES.contains(existing.getStatus())
                    || (existing.getStatus() == ExternalMailImportedMessage.Status.SKIPPED_INITIAL && !migration)) {
                counters.skipped.incrementAndGet();
                advanceCursor(account, envelope, lastSuccessfulUid);
                return ExternalMailClient.HandleResult.SKIPPED;
            }
            retryRecord = existing;
        }
        if (envelope.messageIdHeader() != null && !envelope.messageIdHeader().isBlank()
                && imported.existsByAccount_IdAndRemoteFolderAndMessageIdHeaderIgnoreCaseAndStatusIn(
                account.getId(), envelope.remoteFolder(), envelope.messageIdHeader(), DONE_STATUSES)) {
            counters.skipped.incrementAndGet();
            advanceCursor(account, envelope, lastSuccessfulUid);
            return ExternalMailClient.HandleResult.SKIPPED;
        }

        RawMessageSpool.StoredRawMessage stored;
        try (InputStream in = source.open()) {
            stored = spool.store(in);
        } catch (RawMessageSpool.MessageTooLargeException e) {
            ExternalMailImportedMessage record = retryRecord != null ? retryRecord
                    : baseImported(account, envelope, target, preserveTarget(account, migration));
            fillImported(record, envelope, target, preserveTarget(account, migration));
            record.setStatus(ExternalMailImportedMessage.Status.SKIPPED_TOO_LARGE);
            record.setError(e.getMessage());
            imported.saveAndFlush(record);
            counters.skipped.incrementAndGet();
            counters.failed.incrementAndGet();
            counters.errors.add(envelope.remoteFolder() + "/" + envelope.remoteUid() + ": " + e.getMessage());
            advanceCursor(account, envelope, lastSuccessfulUid);
            return ExternalMailClient.HandleResult.SKIPPED;
        } catch (Exception e) {
            if (isConnectionLoss(e)) {
                // Jakarta-клиент превратит это в NETWORK, а executeClaimed переподключится
                // и повторит текущий UID. Восстановленный обрыв не должен портить итог run.
                throw new ExternalMailClient.StopFolderException(e);
            }
            counters.failed.incrementAndGet();
            counters.errors.add(envelope.remoteFolder() + "/" + envelope.remoteUid() + ": " + safeError(e));
            throw new ExternalMailClient.StopFolderException(e);
        }

        ExternalMailImportedMessage record = retryRecord;
        try {
            if (imported.existsByAccount_IdAndRemoteFolderAndSha256AndStatusIn(
                    account.getId(), envelope.remoteFolder(), stored.sha256(), DONE_STATUSES)) {
                spool.delete(stored.rawRef());
                counters.skipped.incrementAndGet();
                advanceCursor(account, envelope, lastSuccessfulUid);
                return ExternalMailClient.HandleResult.SKIPPED;
            }
            record = record != null ? record : new ExternalMailImportedMessage();
            record.setAccount(account);
            fillImported(record, envelope, target, preserveTarget(account, migration));
            record.setSha256(stored.sha256());
            record.setRawRef(stored.rawRef());
            record.setStatus(ExternalMailImportedMessage.Status.QUEUED);
            record.setError(null);
            record = imported.saveAndFlush(record);
            events.publishConfirmed("mail.inbound.received", new MailPipelineEvents.InboundReceivedEvent(
                            stored.rawRef(),
                            envelope.from() == null || envelope.from().isBlank() ? account.getEmailAddress() : envelope.from(),
                            List.of(owner.email()),
                            "external-mail:" + account.getId(),
                            stored.sizeBytes(), stored.sha256(), Instant.now()),
                    stored.rawRef());
            counters.queued.incrementAndGet();
            advanceCursor(account, envelope, lastSuccessfulUid);
            return ExternalMailClient.HandleResult.QUEUED;
        } catch (Exception e) {
            try {
                spool.delete(stored.rawRef());
            } catch (Exception ignored) {
                // Spool cleanup failure must not hide the real queueing error.
            }
            if (record != null && record.getId() != null) {
                record.setStatus(ExternalMailImportedMessage.Status.FAILED);
                record.setRawRef(null);
                record.setError(safeError(e));
                imported.saveAndFlush(record);
            }
            counters.failed.incrementAndGet();
            counters.errors.add(envelope.remoteFolder() + "/" + envelope.remoteUid() + ": " + safeError(e));
            throw new ExternalMailClient.StopFolderException(e);
        }
    }

    private ExternalMailImportedMessage baseImported(ExternalMailAccount account,
                                                     ExternalMailClient.RemoteMessageEnvelope envelope,
                                                     MailFolder target,
                                                     boolean preserveTarget) {
        ExternalMailImportedMessage record = new ExternalMailImportedMessage();
        record.setAccount(account);
        fillImported(record, envelope, target, preserveTarget);
        return record;
    }

    private void fillImported(ExternalMailImportedMessage record,
                              ExternalMailClient.RemoteMessageEnvelope envelope,
                              MailFolder target,
                              boolean preserveTarget) {
        record.setRemoteFolder(envelope.remoteFolder());
        record.setRemoteUid(envelope.remoteUid());
        record.setUIDValidity(envelope.UIDValidity());
        record.setMessageIdHeader(truncate(envelope.messageIdHeader(), 998));
        record.setTargetFolder(target);
        record.setSeen(envelope.seen());
        record.setAnswered(envelope.answered());
        record.setFlagged(envelope.flagged());
        record.setDraft(envelope.draft());
        record.setDeleted(envelope.deleted());
        record.setPreserveTargetFolder(preserveTarget);
        record.setSourceInternalDate(envelope.internalDate());
    }

    private boolean preserveTarget(ExternalMailAccount account, boolean migration) {
        return migration || account.getSyncScope() == ExternalMailAccount.SyncScope.ALL_FOLDERS;
    }

    private void advanceCursor(ExternalMailAccount account, ExternalMailClient.RemoteMessageEnvelope envelope,
                               AtomicLong lastSuccessfulUid) {
        if (account.getIncomingProtocol() != ExternalMailAccount.Protocol.IMAP) return;
        long uid = parseUid(envelope.remoteUid());
        if (uid > 0) lastSuccessfulUid.accumulateAndGet(uid, Math::max);
    }

    // ------------------------------------------------------------------ folder mapping

    private MailFolder resolveTargetFolder(ExternalMailAccount account,
                                           AuthUserClient.MailboxOwner owner,
                                           ExternalMailClient.RemoteFolderInfo remoteFolder,
                                           ExternalMailFolderState state,
                                           boolean migration) {
        if (state.getTargetFolder() != null) {
            Optional<MailFolder> existing = folders.findByIdAndOwnerId(state.getTargetFolder().getId(), owner.id());
            if (existing.isPresent()) return existing.get();
        }
        MailFolder root = null;
        if (account.getTargetFolder() != null) {
            root = folders.findByIdAndOwnerId(account.getTargetFolder().getId(), owner.id()).orElse(null);
        }
        boolean allFolders = migration || account.getSyncScope() == ExternalMailAccount.SyncScope.ALL_FOLDERS;
        if (root != null && !allFolders) return root;
        if (root == null && remoteFolder.specialUse() != null) {
            return delivery.ensureFolder(owner.id(), remoteFolder.specialUse(), defaultFolderName(remoteFolder.specialUse()));
        }
        return ensureFolderPath(owner.id(), root, remoteFolder.name(), remoteFolder.delimiter());
    }

    private MailFolder ensureFolderPath(UUID ownerId, MailFolder root, String remoteName, String delimiter) {
        String cleanDelimiter = delimiter == null || delimiter.isEmpty() ? "/" : delimiter;
        String[] parts = remoteName.split(Pattern.quote(cleanDelimiter));
        MailFolder parent = root;
        for (String rawPart : parts) {
            String name = sanitizeFolderSegment(rawPart);
            if (name.isBlank()) continue;
            MailFolder found = null;
            for (MailFolder candidate : folders.findAllByOwnerIdOrderBySpecialUseAscNameAsc(ownerId)) {
                UUID parentId = candidate.getParent() == null ? null : candidate.getParent().getId();
                UUID expectedParent = parent == null ? null : parent.getId();
                if (java.util.Objects.equals(parentId, expectedParent) && candidate.getName().equalsIgnoreCase(name)) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                found = new MailFolder();
                found.setOwnerId(ownerId);
                found.setParent(parent);
                found.setName(name);
                found.setSpecialUse(null);
                found.setUidNext(1);
                found.setUidValidity(Math.max(1, Instant.now().getEpochSecond()));
                found.setHighestModseq(1);
                found = folders.saveAndFlush(found);
            }
            parent = found;
        }
        if (parent == null) {
            return delivery.ensureFolder(ownerId, "INBOX", "INBOX");
        }
        return parent;
    }

    private static String sanitizeFolderSegment(String segment) {
        String clean = segment == null ? "" : segment.replace('/', '-').replace('\\', '-').trim();
        if (clean.isBlank() || ".".equals(clean) || "..".equals(clean)) return "Folder";
        return clean.length() <= 120 ? clean : clean.substring(0, 120);
    }

    private static String defaultFolderName(String specialUse) {
        return switch (specialUse.toUpperCase(Locale.ROOT)) {
            case "SENT" -> "Sent";
            case "DRAFTS" -> "Drafts";
            case "TRASH" -> "Trash";
            case "SPAM" -> "Junk";
            case "ARCHIVE" -> "Archive";
            default -> "INBOX";
        };
    }

    // ------------------------------------------------------------------ run/account state

    private void finishRun(ExternalMailAccount account, UUID runId, Counters counters, int folderTotal) {
        Instant now = Instant.now();
        ExternalMailSyncRun run = runs.findById(runId).orElse(null);
        boolean success = counters.failed.get() == 0;
        if (run != null) {
            run.setFoldersCount(folderTotal);
            run.setFetchedCount(counters.fetched.get());
            run.setQueuedCount(counters.queued.get());
            run.setSkippedCount(counters.skipped.get());
            run.setFailedCount(counters.failed.get());
            run.setError(counters.errors.isEmpty() ? null : truncate(String.join("; ", counters.errors), 4000));
            run.setStatus(success ? ExternalMailSyncRun.Status.SUCCESS
                    : (counters.queued.get() > 0 || counters.foldersDone > 0
                    ? ExternalMailSyncRun.Status.PARTIAL : ExternalMailSyncRun.Status.FAILED));
            run.setFinishedAt(now);
            runs.saveAndFlush(run);
        }
        ExternalMailAccount fresh = accounts.findById(account.getId()).orElse(account);
        fresh.setLastSyncAt(now);
        if (success) {
            fresh.setLastSuccessAt(now);
            fresh.setConsecutiveFailures(0);
            fresh.setLastError(null);
            fresh.setNextSyncAt(now.plusSeconds(fresh.getPollIntervalSeconds()));
        } else {
            int failures = fresh.getConsecutiveFailures() + 1;
            fresh.setConsecutiveFailures(failures);
            fresh.setLastError(truncate(String.join("; ", counters.errors), 2000));
            if (counters.permanentCategory != null) {
                fresh.setNextSyncAt(now.plus(Duration.ofDays(1)));
            } else {
                long multiplier = 1L << Math.min(6, failures);
                long delay = Math.min(Duration.ofHours(6).toSeconds(),
                        (long) fresh.getPollIntervalSeconds() * multiplier);
                fresh.setNextSyncAt(now.plusSeconds(Math.max(60, delay)));
            }
        }
        if (!fresh.isEnabled() || !fresh.isSyncEnabled()) fresh.setNextSyncAt(null);
        accounts.saveAndFlush(fresh);
        accountService.audit(null, fresh, success ? "EXTERNAL_SYNC_SUCCEEDED" : "EXTERNAL_SYNC_FAILED",
                "runId=" + runId + ", queued=" + counters.queued.get() + ", skipped=" + counters.skipped.get()
                        + ", failed=" + counters.failed.get());
    }

    private void updateRun(UUID runId, Counters counters, int folderTotal, String error) {
        runs.findById(runId).ifPresent(run -> {
            run.setFoldersCount(folderTotal);
            run.setFetchedCount(counters.fetched.get());
            run.setQueuedCount(counters.queued.get());
            run.setSkippedCount(counters.skipped.get());
            run.setFailedCount(counters.failed.get());
            if (error != null && !error.isBlank()) run.setError(truncate(error, 4000));
            runs.saveAndFlush(run);
        });
    }

    private void heartbeat(UUID accountId, String lockOwner) {
        Instant now = Instant.now();
        jdbc.update("""
                        update external_mail_accounts
                           set sync_lock_until = ?, updated_at = ?
                         where id = ? and sync_lock_owner = ?
                        """, Timestamp.from(now.plus(LOCK_TTL)), Timestamp.from(now), accountId, lockOwner);
    }

    private void releaseClaim(UUID accountId, String lockOwner) {
        try {
            jdbc.update("""
                            update external_mail_accounts
                               set sync_lock_owner = null, sync_lock_until = null, updated_at = ?
                             where id = ? and sync_lock_owner = ?
                            """, Timestamp.from(Instant.now()), accountId, lockOwner);
        } catch (RuntimeException e) {
            log.warn("Не удалось снять lock внешнего ящика {}: {}", accountId, e.getMessage());
        }
    }

    private ExternalMailDtos.SyncRunView toView(ExternalMailSyncRun run) {
        return new ExternalMailDtos.SyncRunView(run.getId(), run.getAccount().getId(), run.getTriggerType(), run.getStatus(),
                run.getStartedAt(), run.getFinishedAt(), run.getFoldersCount(), run.getFetchedCount(),
                run.getQueuedCount(), run.getSkippedCount(), run.getFailedCount(), run.getError());
    }

    private static boolean isConnectionLoss(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof FolderClosedException || current instanceof StoreClosedException
                    || current instanceof ConnectionException) {
                return true;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private static boolean isAccountLevelFailure(ExternalMailClient.ExternalMailClientException e) {
        if (e.category() == ExternalMailClient.ExternalMailClientException.Category.AUTH
                || e.category() == ExternalMailClient.ExternalMailClientException.Category.TLS
                || e.category() == ExternalMailClient.ExternalMailClientException.Category.CONFIG
                || e.category() == ExternalMailClient.ExternalMailClientException.Category.NETWORK) {
            // Запуск использует один Store: после сетевого обрыва остальные папки уже нельзя
            // безопасно читать в этой сессии. Следующий запуск создаст новое подключение.
            return true;
        }
        Throwable current = e;
        while (current != null) {
            if (current instanceof UnknownHostException) return true;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private static long parseUid(String remoteUid) {
        try {
            return Long.parseLong(remoteUid);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String safeError(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return truncate(message.replaceAll("[\\r\\n]+", " "), 500);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
