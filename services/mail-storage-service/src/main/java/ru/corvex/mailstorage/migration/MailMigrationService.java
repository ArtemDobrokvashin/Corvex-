package ru.corvex.mailstorage.migration;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.MigrationDtos;
import ru.corvex.mailstorage.api.dto.ProtocolDtos;
import ru.corvex.mailstorage.domain.ExternalMailAccount;
import ru.corvex.mailstorage.domain.MailMigrationFolder;
import ru.corvex.mailstorage.domain.MailMigrationJob;
import ru.corvex.mailstorage.external.ExternalMailAccountService;
import ru.corvex.mailstorage.external.ExternalMailClient;
import ru.corvex.mailstorage.external.ExternalMailSyncService;
import ru.corvex.mailstorage.repository.MailMigrationFolderRepository;
import ru.corvex.mailstorage.repository.MailMigrationJobRepository;
import ru.corvex.mailstorage.service.ProtocolMailService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Перенос почты из внешнего ящика: оценка объёма до старта, задание с отдельной очередью,
 * пауза/продолжение/отмена и отчёт по папкам.
 *
 * <p>Само копирование писем выполняет {@link ExternalMailSyncService}: там уже есть
 * идемпотентность по SHA-256, сохранение флагов и дат, разбор папок и повторные попытки.
 * Здесь — только то, чего у сбора нет: план, очередь, управление и отчёт.
 */
@Service
public class MailMigrationService {

    private static final Logger log = LoggerFactory.getLogger(MailMigrationService.class);

    /** Сколько писем в папке смотреть для оценки размера: точность важна до старта, но не до письма. */
    static final int PREVIEW_SAMPLE = 50;

    private static final Set<MailMigrationJob.Status> ACTIVE = Set.of(
            MailMigrationJob.Status.QUEUED,
            MailMigrationJob.Status.RUNNING,
            MailMigrationJob.Status.PAUSED);
    private static final Set<MailMigrationJob.Status> RESUMABLE = Set.of(
            MailMigrationJob.Status.PAUSED,
            MailMigrationJob.Status.FAILED,
            MailMigrationJob.Status.PARTIAL);

    private final ExternalMailAccountService accounts;
    private final ExternalMailClient client;
    private final ExternalMailSyncService sync;
    private final ProtocolMailService protocol;
    private final MailMigrationJobRepository jobs;
    private final MailMigrationFolderRepository folders;
    private final ExecutorService executor;
    private final ConcurrentHashMap<UUID, Control> active = new ConcurrentHashMap<>();

    public MailMigrationService(ExternalMailAccountService accounts,
                                ExternalMailClient client,
                                ExternalMailSyncService sync,
                                ProtocolMailService protocol,
                                MailMigrationJobRepository jobs,
                                MailMigrationFolderRepository folders) {
        this.accounts = accounts;
        this.client = client;
        this.sync = sync;
        this.protocol = protocol;
        this.jobs = jobs;
        this.folders = folders;
        // Отдельная очередь: постоянный сбор внешних ящиков идёт в schedulerThreads=2,
        // и архив на 50 ящиков не должен останавливать приход новой почты.
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "corvex-mail-migration");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ preview

    public MigrationDtos.PreviewView preview(UUID actorId, Set<String> authorities,
                                             MigrationDtos.ScopeRequest request) {
        ExternalMailAccount account = accessibleAccount(actorId, authorities, request);
        MigrationScope scope = scopeOf(account, request);
        List<MigrationDtos.FolderEstimate> estimates = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        ExternalMailClient.AccountConfig config = accounts.toConfig(account);
        try (ExternalMailClient.IncomingSession session = client.openIncoming(config)) {
            for (ExternalMailClient.RemoteFolderInfo folder : session.listFolders()) {
                if (scope.skip(folder.name(), folder.specialUse())) {
                    skipped.add(folder.name());
                    continue;
                }
                List<Long> sample = sampleSizes(session, folder);
                estimates.add(new MigrationDtos.FolderEstimate(
                        folder.name(),
                        folder.specialUse(),
                        folder.messageCount(),
                        MigrationScope.estimateBytes(folder.messageCount(), sample)));
            }
        } catch (ExternalMailClient.ExternalMailClientException e) {
            throw upstream(e);
        }

        long totalMessages = estimates.stream().mapToLong(MigrationDtos.FolderEstimate::messageCount).sum();
        long totalBytes = estimates.stream().mapToLong(MigrationDtos.FolderEstimate::estimatedBytes).sum();
        long[] quota = quota(account.getOwnerId());
        long quotaBytes = quota[0];
        long usedBytes = quota[1];
        boolean fits = quotaBytes <= 0 || usedBytes + totalBytes <= quotaBytes;

        List<String> warnings = new ArrayList<>();
        if (!skipped.isEmpty()) {
            warnings.add("Пропущены папки, которые дублируют другие: " + String.join(", ", skipped));
        }
        if (!fits) {
            warnings.add("Архив не помещается в квоту: не хватает "
                    + formatBytes(usedBytes + totalBytes - quotaBytes)
                    + ". Перенос остановится на границе квоты — поднимите квоту или выберите меньше папок.");
        }
        if (account.getIncomingProtocol() == ExternalMailAccount.Protocol.POP3) {
            warnings.add("POP3 отдаёт только INBOX: дерево папок и флаги не перенесутся.");
        }
        return new MigrationDtos.PreviewView(account.getId(), request.includeAllMail() != null
                && request.includeAllMail(), estimates, skipped, totalMessages, totalBytes,
                quotaBytes, usedBytes, fits, warnings);
    }

    // ------------------------------------------------------------------ jobs

    public MigrationDtos.JobView create(UUID actorId, Set<String> authorities,
                                        MigrationDtos.ScopeRequest request) {
        ExternalMailAccount account = accessibleAccount(actorId, authorities, request);
        jobs.findFirstByAccount_IdAndStatusIn(account.getId(), ACTIVE).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "По этому ящику уже есть задание переноса: " + existing.getId());
        });

        MigrationDtos.PreviewView preview = preview(actorId, authorities, request);
        if (preview.folders().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В источнике нет папок для переноса");
        }

        MailMigrationJob job = new MailMigrationJob();
        job.setOwnerId(account.getOwnerId());
        job.setAccount(account);
        job.setCreatedBy(actorId);
        job.setStatus(MailMigrationJob.Status.QUEUED);
        job.setMode(MailMigrationJob.Mode.FULL);
        job.setIncludeAllMail(preview.includeAllMail());
        job.setSkipFolders(MigrationScope.joinCsv(request.skipFolders()));
        job.setEstimatedMessages(preview.totalMessages());
        job.setEstimatedBytes(preview.totalBytes());
        Instant now = Instant.now();
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job = jobs.saveAndFlush(job);

        for (MigrationDtos.FolderEstimate estimate : preview.folders()) {
            MailMigrationFolder row = new MailMigrationFolder();
            row.setJob(job);
            row.setRemoteFolder(estimate.remoteFolder());
            row.setSpecialUse(estimate.specialUse());
            row.setEstimatedMessages(estimate.messageCount());
            row.setEstimatedBytes(estimate.estimatedBytes());
            folders.save(row);
        }
        folders.flush();

        accounts.audit(actorId, account, "MAIL_MIGRATION_CREATED",
                "jobId=" + job.getId() + ", folders=" + preview.folders().size()
                        + ", estimatedBytes=" + preview.totalBytes());
        submit(job.getId(), false);
        return view(job);
    }

    public List<MigrationDtos.JobView> list(UUID actorId, Set<String> authorities) {
        return jobs.findTop20ByOwnerIdOrderByCreatedAtDesc(actorId).stream()
                .map(this::view)
                .toList();
    }

    public MigrationDtos.JobView get(UUID actorId, Set<String> authorities, UUID jobId) {
        return view(accessibleJob(actorId, authorities, jobId));
    }

    public MigrationDtos.ReportView report(UUID actorId, Set<String> authorities, UUID jobId) {
        MailMigrationJob job = accessibleJob(actorId, authorities, jobId);
        long[] quota = quota(job.getOwnerId());
        long remaining = quota[0] <= 0 ? -1 : Math.max(0, quota[0] - quota[1]);
        List<String> notes = new ArrayList<>();
        notes.add("Объём до старта — оценка по выборке до " + PREVIEW_SAMPLE + " писем в папке.");
        if (!job.isIncludeAllMail()) {
            notes.add("All Mail, Important и Starred пропущены: письма из них уже есть в своих папках.");
        }
        if (job.getStatus() == MailMigrationJob.Status.PAUSED) {
            notes.add("Продолжение начнётся с последнего перенесённого письма, повторной загрузки не будет.");
        }
        if (job.getStatus() == MailMigrationJob.Status.FAILED) {
            notes.add("Повторный запуск безопасен: уже перенесённые письма пропускаются по SHA-256.");
        }
        return new MigrationDtos.ReportView(view(job), quota[0], quota[1], remaining, notes);
    }

    public MigrationDtos.JobView pause(UUID actorId, Set<String> authorities, UUID jobId) {
        MailMigrationJob job = accessibleJob(actorId, authorities, jobId);
        Control control = active.get(jobId);
        if (control == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Задание не выполняется");
        }
        control.pause();
        job.setStatus(MailMigrationJob.Status.PAUSED);
        job.setUpdatedAt(Instant.now());
        jobs.saveAndFlush(job);
        accounts.audit(actorId, job.getAccount(), "MAIL_MIGRATION_PAUSED", "jobId=" + jobId);
        return view(job);
    }

    public MigrationDtos.JobView cancel(UUID actorId, Set<String> authorities, UUID jobId) {
        MailMigrationJob job = accessibleJob(actorId, authorities, jobId);
        if (job.finished()) return view(job);
        Control control = active.get(jobId);
        if (control != null) {
            control.cancel();
        } else if (job.getStatus() == MailMigrationJob.Status.QUEUED) {
            job.setStatus(MailMigrationJob.Status.CANCELLED);
            job.setFinishedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            jobs.saveAndFlush(job);
        }
        accounts.audit(actorId, job.getAccount(), "MAIL_MIGRATION_CANCELLED", "jobId=" + jobId);
        return view(job);
    }

    public MigrationDtos.JobView resume(UUID actorId, Set<String> authorities, UUID jobId) {
        MailMigrationJob job = accessibleJob(actorId, authorities, jobId);
        if (!RESUMABLE.contains(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Продолжить можно только приостановленное или неудавшееся задание");
        }
        if (active.containsKey(jobId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Задание уже выполняется");
        }
        job.setStatus(MailMigrationJob.Status.QUEUED);
        job.setError(null);
        job.setUpdatedAt(Instant.now());
        jobs.saveAndFlush(job);
        accounts.audit(actorId, job.getAccount(), "MAIL_MIGRATION_RESUMED", "jobId=" + jobId);
        submit(jobId, true);
        return view(job);
    }

    // ------------------------------------------------------------------ worker

    private void submit(UUID jobId, boolean resume) {
        try {
            executor.submit(() -> run(jobId, resume));
        } catch (RejectedExecutionException e) {
            MailMigrationJob job = jobs.findById(jobId).orElse(null);
            if (job != null) {
                job.setStatus(MailMigrationJob.Status.FAILED);
                job.setError("Очередь переноса переполнена: повторите позже");
                job.setFinishedAt(Instant.now());
                job.setUpdatedAt(Instant.now());
                jobs.saveAndFlush(job);
            }
        }
    }

    private void run(UUID jobId, boolean resume) {
        MailMigrationJob job = jobs.findById(jobId).orElse(null);
        if (job == null) return;
        if (job.getStatus() == MailMigrationJob.Status.CANCELLED || job.finished()) return;
        MailMigrationJob running = job;
        running.setStatus(MailMigrationJob.Status.RUNNING);
        running.setStartedAt(running.getStartedAt() == null ? Instant.now() : running.getStartedAt());
        running.setUpdatedAt(Instant.now());
        running = jobs.saveAndFlush(running);
        Control control = new Control(running, resume,
                MigrationScope.of(running.getAccount().getProvider(), running.isIncludeAllMail(),
                        running.getSkipFolders()));
        active.put(jobId, control);
        try {
            ExternalMailSyncService.MigrationOutcome outcome =
                    sync.runMigration(running.getAccount().getId(), control);
            MailMigrationJob finished = jobs.findById(jobId).orElse(running);
            finished.setRunId(outcome.run() == null ? finished.getRunId() : outcome.run().getId());
            finished.setStatus(control.cancelled() ? MailMigrationJob.Status.CANCELLED
                    : control.paused() ? MailMigrationJob.Status.PAUSED
                    : completionStatus(jobId));
            finished.setError(outcome.run() == null ? null : truncate(outcome.run().getError(), 4000));
            if (finished.finished()) finished.setFinishedAt(Instant.now());
            finished.setUpdatedAt(Instant.now());
            jobs.saveAndFlush(finished);
            accounts.audit(null, running.getAccount(), "MAIL_MIGRATION_" + finished.getStatus(),
                    "jobId=" + jobId + ", migrated=" + migratedMessages(jobId));
        } catch (ResponseStatusException e) {
            fail(jobId, running, e.getReason());
        } catch (RuntimeException e) {
            log.warn("Mail migration job {} failed: {}", jobId, e.toString());
            fail(jobId, running, e.getMessage());
        } finally {
            active.remove(jobId);
        }
    }

    private void fail(UUID jobId, MailMigrationJob job, String reason) {
        MailMigrationJob failed = jobs.findById(jobId).orElse(job);
        failed.setStatus(MailMigrationJob.Status.FAILED);
        failed.setError(truncate(reason == null ? "Перенос остановлен внутренней ошибкой" : reason, 4000));
        failed.setFinishedAt(Instant.now());
        failed.setUpdatedAt(Instant.now());
        jobs.saveAndFlush(failed);
        accounts.audit(null, job.getAccount(), "MAIL_MIGRATION_FAILED", "jobId=" + jobId);
    }

    private MailMigrationJob.Status completionStatus(UUID jobId) {
        List<MailMigrationFolder> rows = folders.findByJob_IdOrderByRemoteFolderAsc(jobId);
        boolean failed = rows.stream().anyMatch(row -> row.getState() == MailMigrationFolder.State.FAILED);
        int migrated = rows.stream().mapToInt(MailMigrationFolder::getMigratedMessages).sum();
        if (!failed) return MailMigrationJob.Status.DONE;
        return migrated > 0 ? MailMigrationJob.Status.PARTIAL : MailMigrationJob.Status.FAILED;
    }

    private int migratedMessages(UUID jobId) {
        return folders.findByJob_IdOrderByRemoteFolderAsc(jobId).stream()
                .mapToInt(MailMigrationFolder::getMigratedMessages).sum();
    }

    /** Обратная связь от синхронизации: без неё отчёт знал бы только общий счётчик запуска. */
    private final class Control implements ExternalMailSyncService.MigrationControl {
        private final MailMigrationJob job;
        private final boolean resume;
        private final MigrationScope scope;
        private volatile boolean paused;
        private volatile boolean cancelled;

        private Control(MailMigrationJob job, boolean resume, MigrationScope scope) {
            this.job = job;
            this.resume = resume;
            this.scope = scope;
        }

        @Override
        public boolean paused() {
            return paused;
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public boolean resume() {
            return resume;
        }

        @Override
        public boolean skipFolder(String remoteFolder, String specialUse) {
            return scope.skip(remoteFolder, specialUse);
        }

        @Override
        public void folderStarted(String remoteFolder) {
            folder(remoteFolder).ifPresent(row -> {
                row.setState(MailMigrationFolder.State.RUNNING);
                row.setStartedAt(row.getStartedAt() == null ? Instant.now() : row.getStartedAt());
                folders.saveAndFlush(row);
            });
        }

        @Override
        public void folderFinished(String remoteFolder, int migrated, int skipped, String error) {
            folder(remoteFolder).ifPresent(row -> {
                row.setMigratedMessages(row.getMigratedMessages() + Math.max(0, migrated));
                row.setSkippedMessages(row.getSkippedMessages() + Math.max(0, skipped));
                row.setState(error == null ? MailMigrationFolder.State.DONE : MailMigrationFolder.State.FAILED);
                row.setError(truncate(error, 2000));
                row.setFinishedAt(Instant.now());
                folders.saveAndFlush(row);
            });
        }

        private java.util.Optional<MailMigrationFolder> folder(String remoteFolder) {
            return folders.findByJob_IdAndRemoteFolder(job.getId(), remoteFolder);
        }

        void pause() {
            paused = true;
        }

        void cancel() {
            cancelled = true;
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<Long> sampleSizes(ExternalMailClient.IncomingSession session,
                                   ExternalMailClient.RemoteFolderInfo folder) {
        int sample = MigrationScope.sampleSize(folder.messageCount(), PREVIEW_SAMPLE);
        if (sample == 0) return List.of();
        try {
            return session.listMessageEnvelopes(folder.name(), sample).stream()
                    .map(ExternalMailClient.RemoteMessageEnvelope::sizeBytes)
                    .toList();
        } catch (ExternalMailClient.ExternalMailClientException e) {
            throw upstream(e);
        }
    }

    private ExternalMailAccount accessibleAccount(UUID actorId, Set<String> authorities,
                                                  MigrationDtos.ScopeRequest request) {
        if (request == null || request.accountId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите внешний ящик");
        }
        return accounts.accessibleAccount(actorId, authorities, request.accountId());
    }

    private MailMigrationJob accessibleJob(UUID actorId, Set<String> authorities, UUID jobId) {
        MailMigrationJob job = jobs.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Задание не найдено"));
        boolean admin = authorities != null && authorities.contains(ExternalMailAccountService.ADMIN_PERMISSION);
        if (!job.getOwnerId().equals(actorId) && !admin) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Задание не найдено");
        }
        return job;
    }

    private MigrationScope scopeOf(ExternalMailAccount account, MigrationDtos.ScopeRequest request) {
        return new MigrationScope(account.getProvider(),
                request.includeAllMail() != null && request.includeAllMail(),
                request.skipFolders());
    }

    /** @return [quotaBytes, usedBytes]; 0 означает «без лимита» или «не удалось узнать». */
    private long[] quota(UUID ownerId) {
        try {
            ProtocolDtos.QuotaView quota = protocol.quota(ownerId);
            return new long[]{quota.quotaBytes(), quota.usedBytes()};
        } catch (RuntimeException e) {
            log.warn("Не удалось получить квоту ящика {}: {}", ownerId, e.toString());
            return new long[]{0, 0};
        }
    }

    private MigrationDtos.JobView view(MailMigrationJob job) {
        List<MigrationDtos.FolderView> rows = folders.findByJob_IdOrderByRemoteFolderAsc(job.getId()).stream()
                .map(row -> new MigrationDtos.FolderView(row.getId(), row.getRemoteFolder(),
                        row.getSpecialUse(), row.getState().name(), row.getEstimatedMessages(),
                        row.getEstimatedBytes(), row.getMigratedMessages(), row.getSkippedMessages(),
                        row.getError()))
                .toList();
        int migrated = rows.stream().mapToInt(MigrationDtos.FolderView::migratedMessages).sum();
        int skipped = rows.stream().mapToInt(MigrationDtos.FolderView::skippedMessages).sum();
        return new MigrationDtos.JobView(job.getId(), job.getAccount().getId(),
                job.getAccount().getEmailAddress(), job.getStatus().name(), job.getMode().name(),
                job.isIncludeAllMail(), job.getEstimatedMessages(), job.getEstimatedBytes(),
                migrated, skipped, job.getError(), job.getCreatedAt(), job.getStartedAt(),
                job.getFinishedAt(), rows);
    }

    private ResponseStatusException upstream(ExternalMailClient.ExternalMailClientException e) {
        HttpStatus status = switch (e.category()) {
            case AUTH -> HttpStatus.BAD_GATEWAY;
            case TLS, NETWORK -> HttpStatus.BAD_GATEWAY;
            case CONFIG -> HttpStatus.BAD_REQUEST;
            case PROTOCOL -> HttpStatus.BAD_GATEWAY;
        };
        return new ResponseStatusException(status, e.category() + ": " + e.getMessage());
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.1f КиБ", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1f МиБ", mb);
        return String.format(java.util.Locale.ROOT, "%.1f ГиБ", mb / 1024.0);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
