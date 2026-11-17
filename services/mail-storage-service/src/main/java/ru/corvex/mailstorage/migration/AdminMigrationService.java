package ru.corvex.mailstorage.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.AdminMigrationDtos;
import ru.corvex.mailstorage.api.dto.ProtocolDtos;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.MailMigrationFolder;
import ru.corvex.mailstorage.domain.MailMigrationInvitation;
import ru.corvex.mailstorage.domain.MailMigrationJob;
import ru.corvex.mailstorage.domain.UserOnboarding;
import ru.corvex.mailstorage.external.ExternalMailAccountService;
import ru.corvex.mailstorage.repository.MailMigrationFolderRepository;
import ru.corvex.mailstorage.repository.MailMigrationInvitationRepository;
import ru.corvex.mailstorage.repository.MailMigrationJobRepository;
import ru.corvex.mailstorage.repository.UserOnboardingRepository;
import ru.corvex.mailstorage.service.ProtocolMailService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Админский контур переноса: кто уже начал, кто ещё нет, массовый CSV и «поднять квоту».
 *
 * <p>Паролей здесь нет ни в файле, ни в API: CSV только приглашает сотрудника и подсказывает
 * источник, пароль приложения вводит владелец ящика в мастере. Это осознанное ограничение,
 * а не недоделка: администратор не должен знать чужие пароли.
 */
@Service
public class AdminMigrationService {

    private static final Logger log = LoggerFactory.getLogger(AdminMigrationService.class);
    private static final long MAX_QUOTA_BYTES = 10L * 1024 * 1024 * 1024 * 1024;
    private static final Set<MailMigrationJob.Status> RUNNING = Set.of(
            MailMigrationJob.Status.QUEUED,
            MailMigrationJob.Status.RUNNING,
            MailMigrationJob.Status.PAUSED);

    private final MailMigrationInvitationRepository invitations;
    private final MailMigrationJobRepository jobs;
    private final MailMigrationFolderRepository folders;
    private final UserOnboardingRepository onboarding;
    private final AuthUserClient auth;
    private final ProtocolMailService protocol;
    private final ExternalMailAccountService accounts;

    public AdminMigrationService(MailMigrationInvitationRepository invitations,
                                 MailMigrationJobRepository jobs,
                                 MailMigrationFolderRepository folders,
                                 UserOnboardingRepository onboarding,
                                 AuthUserClient auth,
                                 ProtocolMailService protocol,
                                 ExternalMailAccountService accounts) {
        this.invitations = invitations;
        this.jobs = jobs;
        this.folders = folders;
        this.onboarding = onboarding;
        this.auth = auth;
        this.protocol = protocol;
        this.accounts = accounts;
    }

    public AdminMigrationDtos.Summary summary(UUID actorId, Set<String> authorities) {
        requireAdmin(authorities);
        List<MailMigrationInvitation> invited = invitations.findAll().stream()
                .sorted(Comparator.comparing(MailMigrationInvitation::getCreatedAt).reversed())
                .toList();

        // Владельцы, у которых есть приглашение или задание. Больше ниоткуда список
        // не берётся: сводка нужна про перенос, а не про всех сотрудников подряд.
        Map<UUID, MailMigrationInvitation> byOwner = new LinkedHashMap<>();
        for (MailMigrationInvitation invitation : invited) {
            byOwner.putIfAbsent(invitation.getOwnerId(), invitation);
        }
        for (MailMigrationJob job : jobs.findAll()) {
            byOwner.putIfAbsent(job.getOwnerId(), null);
        }

        List<UUID> owners = byOwner.keySet().stream().limit(200).toList();
        Map<UUID, ProtocolDtos.MailboxUsageView> usage = new LinkedHashMap<>();
        if (!owners.isEmpty()) {
            try {
                for (ProtocolDtos.MailboxUsageView row : protocol.usage(owners)) {
                    usage.put(row.ownerId(), row);
                }
            } catch (RuntimeException e) {
                log.warn("Не удалось посчитать занятое место для сводки переноса: {}", e.toString());
            }
        }

        // Последнее задание читается по одному разу на владельца: сводка показывает и статус,
        // и счётчик, а второй запрос за тем же заданием ничего не добавляет.
        Map<UUID, MailMigrationJob> latest = new LinkedHashMap<>();
        for (UUID ownerId : owners) {
            latest.put(ownerId, jobs.findTop20ByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                    .findFirst().orElse(null));
        }
        Map<UUID, Integer> migratedPerJob = migratedTotalsByJob(latest.values());

        List<AdminMigrationDtos.Row> rows = new ArrayList<>();
        int notStarted = 0;
        int running = 0;
        int done = 0;
        int failed = 0;
        long migrated = 0;
        for (UUID ownerId : owners) {
            MailMigrationInvitation invitation = byOwner.get(ownerId);
            // auth-service может быть недоступен: сводка должна показать хотя бы то,
            // что известно из приглашений и заданий, а не падать целиком.
            Optional<AuthUserClient.MailboxOwner> owner = owner(ownerId);
            MailMigrationJob job = latest.get(ownerId);
            UserOnboarding onboardingRow = onboarding.findById(ownerId).orElse(null);
            ProtocolDtos.MailboxUsageView used = usage.get(ownerId);

            MailMigrationJob.Status jobStatus = job == null ? null : job.getStatus();
            if (jobStatus == null) {
                notStarted++;
            } else if (RUNNING.contains(jobStatus)) {
                running++;
            } else if (jobStatus == MailMigrationJob.Status.DONE || jobStatus == MailMigrationJob.Status.PARTIAL) {
                done++;
            } else {
                failed++;
            }
            int migratedMessages = job == null ? 0 : migratedPerJob.getOrDefault(job.getId(), 0);
            migrated += migratedMessages;

            rows.add(new AdminMigrationDtos.Row(
                    ownerId,
                    owner.map(AuthUserClient.MailboxOwner::email)
                            .orElse(invitation == null ? "" : invitation.getEmail()),
                    owner.map(AuthUserClient.MailboxOwner::displayName).orElse(""),
                    invitation == null ? null : invitation.getStatus().name(),
                    onboardingRow == null ? null : onboardingRow.getMigrationState().name(),
                    jobStatus == null ? null : jobStatus.name(),
                    job == null ? null : job.getId(),
                    migratedMessages,
                    owner.map(AuthUserClient.MailboxOwner::quotaBytes).orElse(0L),
                    used == null ? 0 : used.usedBytes(),
                    invitation == null ? null : invitation.getSourceHost(),
                    invitation == null ? null : invitation.getSourceLogin()));
        }

        List<String> notes = new ArrayList<>();
        notes.add("Пароли в CSV не принимаются: сотрудник вводит пароль приложения сам.");
        if (byOwner.size() > owners.size()) {
            notes.add("Показаны первые 200 сотрудников; используйте CSV, чтобы работать с остальными.");
        }
        return new AdminMigrationDtos.Summary(rows,
                new AdminMigrationDtos.Totals(rows.size(), invited.size(), notStarted, running, done, failed, migrated),
                notes);
    }

    @Transactional
    public AdminMigrationDtos.BulkResult bulk(UUID actorId, Set<String> authorities, byte[] content) {
        requireAdmin(authorities);
        MigrationBulkCsv.Result parsed = MigrationBulkCsv.parse(content);
        int invited = 0;
        int skipped = 0;
        List<MigrationBulkCsv.Issue> issues = new ArrayList<>(parsed.issues());

        for (MigrationBulkCsv.Row row : parsed.rows()) {
            Optional<AuthUserClient.MailboxOwner> owner = auth.findByEmail(row.email());
            if (owner.isEmpty()) {
                issues.add(new MigrationBulkCsv.Issue(row.line(), row.email(), "сотрудник с таким адресом не найден"));
                skipped++;
                continue;
            }
            MailMigrationInvitation invitation = invitations
                    .findByOwnerIdAndEmailIgnoreCase(owner.get().id(), row.email())
                    .orElseGet(() -> {
                        MailMigrationInvitation created = new MailMigrationInvitation();
                        created.setOwnerId(owner.get().id());
                        created.setEmail(row.email());
                        created.setCreatedBy(actorId);
                        created.setCreatedAt(Instant.now());
                        return created;
                    });
            invitation.setSourceHost(row.sourceHost());
            invitation.setSourceLogin(row.sourceLogin());
            invitation.setStatus(MailMigrationInvitation.Status.PENDING);
            invitation.setUpdatedAt(Instant.now());
            invitations.save(invitation);

            UserOnboarding onboardingRow = onboarding.findById(owner.get().id()).orElseGet(() -> {
                UserOnboarding created = new UserOnboarding();
                created.setUserId(owner.get().id());
                return created;
            });
            // Решение «мне нечего переносить» администратор своим CSV перекрывает:
            // иначе приглашение не показалось бы сотруднику вовсе.
            if (onboardingRow.getMigrationState() == UserOnboarding.MigrationState.DISMISSED
                    || onboardingRow.getMigrationState() == UserOnboarding.MigrationState.NOT_NEEDED) {
                onboardingRow.setMigrationState(UserOnboarding.MigrationState.PENDING);
                onboardingRow.setDismissedAt(null);
                onboardingRow.setNotNeededAt(null);
            }
            onboardingRow.setUpdatedAt(Instant.now());
            onboarding.save(onboardingRow);
            invited++;
        }
        invitations.flush();
        onboarding.flush();

        accounts.audit(actorId, null, null, "MAIL_MIGRATION_BULK_INVITED",
                "invited=" + invited + ", skipped=" + skipped + ", truncated=" + parsed.truncated());
        return new AdminMigrationDtos.BulkResult(parsed.rows().size() + parsed.issues().size(),
                invited, skipped, parsed.truncated(), issues);
    }

    @Transactional
    public AdminMigrationDtos.QuotaBumpResult quotaBump(UUID actorId, Set<String> authorities, UUID ownerId,
                                                        AdminMigrationDtos.QuotaBumpRequest request) {
        requireAdmin(authorities);
        if (request == null || (request.quotaBytes() == null && request.addBytes() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Укажите новую квоту или сколько байт добавить");
        }
        AuthUserClient.MailboxOwner owner = auth.findById(ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Сотрудник не найден"));
        long current = owner.quotaBytes();
        long next = request.quotaBytes() != null ? request.quotaBytes() : current + request.addBytes();
        if (next <= 0 || next > MAX_QUOTA_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Квота должна быть больше нуля и не больше 10 ТиБ");
        }
        auth.updateQuota(ownerId, next);
        long used = protocol.usage(List.of(ownerId)).stream()
                .mapToLong(ProtocolDtos.MailboxUsageView::usedBytes)
                .findFirst().orElse(0);
        accounts.audit(actorId, null, ownerId, "MAIL_MIGRATION_QUOTA_BUMPED",
                "from=" + current + ", to=" + next + ", used=" + used);
        return new AdminMigrationDtos.QuotaBumpResult(ownerId, next, used, used <= next,
                Math.max(0, next - used));
    }

    /**
     * Перенесённые письма считаются по папкам: у задания нет своего счётчика, а сумму
     * по строкам обновляет синхронизация. Иначе сводка показывала бы ноль на успешном переносе.
     */
    private Map<UUID, Integer> migratedTotalsByJob(Collection<MailMigrationJob> jobsOfOwners) {
        List<UUID> jobIds = jobsOfOwners.stream()
                .filter(Objects::nonNull)
                .map(MailMigrationJob::getId)
                .toList();
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> totals = new HashMap<>();
        for (MailMigrationFolder folder : folders.findByJob_IdIn(jobIds)) {
            totals.merge(folder.getJob().getId(), folder.getMigratedMessages(), Integer::sum);
        }
        return totals;
    }

    private Optional<AuthUserClient.MailboxOwner> owner(UUID ownerId) {
        try {
            return auth.findById(ownerId);
        } catch (RuntimeException e) {
            log.warn("auth-service недоступен для {}: {}", ownerId, e.toString());
            return Optional.empty();
        }
    }

    private static void requireAdmin(Set<String> authorities) {
        if (authorities == null || !authorities.contains(ExternalMailAccountService.ADMIN_PERMISSION)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Нет права администрировать перенос почты (" + ExternalMailAccountService.ADMIN_PERMISSION + ")");
        }
    }
}
