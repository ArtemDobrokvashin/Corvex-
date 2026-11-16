package ru.corvex.mailstorage.onboarding;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.OnboardingDtos;
import ru.corvex.mailstorage.domain.MailMigrationInvitation;
import ru.corvex.mailstorage.domain.MailMigrationJob;
import ru.corvex.mailstorage.domain.UserOnboarding;
import ru.corvex.mailstorage.repository.ExternalMailAccountRepository;
import ru.corvex.mailstorage.repository.MailMigrationInvitationRepository;
import ru.corvex.mailstorage.repository.MailMigrationJobRepository;
import ru.corvex.mailstorage.repository.UserOnboardingRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Серверное состояние предложения «Перенести почту».
 *
 * <p>Без него мастер показывается либо никогда, либо при каждом входе: единственный
 * существовавший флаг {@code mustChangePassword} снимается после смены пароля.
 * Состояние хранит только решение пользователя; факт переноса выводится из заданий,
 * поэтому «выполнено» нельзя выставить вручную и нельзя потерять при переустановке.
 */
@Service
public class OnboardingService {

    private final UserOnboardingRepository onboarding;
    private final MailMigrationJobRepository jobs;
    private final ExternalMailAccountRepository accounts;
    private final MailMigrationInvitationRepository invitations;

    public OnboardingService(UserOnboardingRepository onboarding,
                             MailMigrationJobRepository jobs,
                             ExternalMailAccountRepository accounts,
                             MailMigrationInvitationRepository invitations) {
        this.onboarding = onboarding;
        this.jobs = jobs;
        this.accounts = accounts;
        this.invitations = invitations;
    }

    @Transactional
    public OnboardingDtos.OnboardingView state(UUID userId) {
        UserOnboarding row = row(userId);
        MailMigrationJob activeJob = jobs.findFirstByOwnerIdAndStatusIn(userId,
                        List.of(MailMigrationJob.Status.QUEUED, MailMigrationJob.Status.RUNNING))
                .orElse(null);
        MailMigrationJob lastJob = jobs.findTop20ByOwnerIdOrderByCreatedAtDesc(userId).stream()
                .findFirst().orElse(null);

        boolean completed = lastJob != null
                && (lastJob.getStatus() == MailMigrationJob.Status.DONE
                || lastJob.getStatus() == MailMigrationJob.Status.PARTIAL);
        UserOnboarding.MigrationState stored = row.getMigrationState();
        UserOnboarding.MigrationState effective = completed ? UserOnboarding.MigrationState.DONE : stored;
        if (completed && stored != UserOnboarding.MigrationState.DONE) {
            row.setMigrationState(UserOnboarding.MigrationState.DONE);
            row.setCompletedAt(Instant.now());
            row.setUpdatedAt(Instant.now());
            onboarding.saveAndFlush(row);
        }

        boolean suggested = effective == UserOnboarding.MigrationState.PENDING
                || effective == UserOnboarding.MigrationState.DISMISSED;
        UUID activeJobId = activeJob == null ? null : activeJob.getId();
        if (activeJobId != null) suggested = false;

        MailMigrationInvitation invitation = invitations.findFirstByOwnerIdOrderByCreatedAtDesc(userId)
                .orElse(null);
        return new OnboardingDtos.OnboardingView(
                effective.name(),
                suggested,
                activeJobId,
                lastJob == null ? null : lastJob.getStatus().name(),
                row.getDismissedAt(),
                accounts.findAllByOwnerIdOrderByCreatedAtAsc(userId).stream()
                        .map(account -> new OnboardingDtos.AccountRef(account.getId(),
                                account.getEmailAddress(), account.getProvider().name()))
                        .toList(),
                invitation == null ? null : invitation.getEmail(),
                invitation == null ? null : invitation.getSourceHost(),
                invitation == null ? null : invitation.getSourceLogin());
    }

    @Transactional
    public OnboardingDtos.OnboardingView dismiss(UUID userId) {
        UserOnboarding row = row(userId);
        if (row.getMigrationState() == UserOnboarding.MigrationState.NOT_NEEDED
                || row.getMigrationState() == UserOnboarding.MigrationState.DONE) {
            return state(userId);
        }
        row.setMigrationState(UserOnboarding.MigrationState.DISMISSED);
        row.setDismissedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        onboarding.saveAndFlush(row);
        return state(userId);
    }

    @Transactional
    public OnboardingDtos.OnboardingView notNeeded(UUID userId) {
        UserOnboarding row = row(userId);
        row.setMigrationState(UserOnboarding.MigrationState.NOT_NEEDED);
        row.setNotNeededAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        onboarding.saveAndFlush(row);
        return state(userId);
    }

    /** Возврат к предложению из настроек: «позже» и «нечего переносить» обратимы. */
    @Transactional
    public OnboardingDtos.OnboardingView reset(UUID userId) {
        UserOnboarding row = row(userId);
        if (row.getMigrationState() == UserOnboarding.MigrationState.DONE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Перенос уже выполнялся");
        }
        row.setMigrationState(UserOnboarding.MigrationState.PENDING);
        row.setDismissedAt(null);
        row.setNotNeededAt(null);
        row.setUpdatedAt(Instant.now());
        onboarding.saveAndFlush(row);
        return state(userId);
    }

    private UserOnboarding row(UUID userId) {
        return onboarding.findById(userId).orElseGet(() -> {
            UserOnboarding created = new UserOnboarding();
            created.setUserId(userId);
            created.setMigrationState(UserOnboarding.MigrationState.PENDING);
            created.setUpdatedAt(Instant.now());
            return onboarding.saveAndFlush(created);
        });
    }

}
