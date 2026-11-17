package ru.corvex.mailstorage.migration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.AdminMigrationDtos;
import ru.corvex.mailstorage.api.dto.ProtocolDtos;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.MailMigrationFolder;
import ru.corvex.mailstorage.domain.MailMigrationInvitation;
import ru.corvex.mailstorage.domain.MailMigrationJob;
import ru.corvex.mailstorage.external.ExternalMailAccountService;
import ru.corvex.mailstorage.repository.MailMigrationFolderRepository;
import ru.corvex.mailstorage.repository.MailMigrationInvitationRepository;
import ru.corvex.mailstorage.repository.MailMigrationJobRepository;
import ru.corvex.mailstorage.repository.UserOnboardingRepository;
import ru.corvex.mailstorage.service.ProtocolMailService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Сводка админского экрана «Перенос». Отдельный тест нужен потому, что счётчик перенесённых
 * писем живёт не в задании, а в его папках: без суммирования сводка показывала бы ноль.
 */
class AdminMigrationServiceTest {

    private static final UUID ADMIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OWNER_DONE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OWNER_IDLE = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private final MailMigrationInvitationRepository invitations = mock(MailMigrationInvitationRepository.class);
    private final MailMigrationJobRepository jobs = mock(MailMigrationJobRepository.class);
    private final MailMigrationFolderRepository folders = mock(MailMigrationFolderRepository.class);
    private final UserOnboardingRepository onboarding = mock(UserOnboardingRepository.class);
    private final AuthUserClient auth = mock(AuthUserClient.class);
    private final ProtocolMailService protocol = mock(ProtocolMailService.class);
    private final ExternalMailAccountService accounts = mock(ExternalMailAccountService.class);
    private final AdminMigrationService service = new AdminMigrationService(invitations, jobs, folders,
            onboarding, auth, protocol, accounts);

    private static MailMigrationInvitation invitation(UUID ownerId, String email, Instant createdAt) {
        MailMigrationInvitation invitation = new MailMigrationInvitation();
        invitation.setOwnerId(ownerId);
        invitation.setEmail(email);
        invitation.setCreatedAt(createdAt);
        invitation.setStatus(MailMigrationInvitation.Status.PENDING);
        invitation.setSourceHost("imap.old.example");
        invitation.setSourceLogin("ivan");
        return invitation;
    }

    private static MailMigrationJob job(UUID ownerId, MailMigrationJob.Status status) {
        MailMigrationJob job = new MailMigrationJob();
        job.setId(UUID.randomUUID());
        job.setOwnerId(ownerId);
        job.setStatus(status);
        return job;
    }

    private static MailMigrationFolder folder(MailMigrationJob job, String remote, int migrated) {
        MailMigrationFolder folder = new MailMigrationFolder();
        folder.setJob(job);
        folder.setRemoteFolder(remote);
        folder.setMigratedMessages(migrated);
        return folder;
    }

    private static AuthUserClient.MailboxOwner owner(UUID id, String email, long quota) {
        return new AuthUserClient.MailboxOwner(id, email, "Иван", "ACTIVE", quota);
    }

    private void baseInvitations() {
        when(invitations.findAll()).thenReturn(List.of(
                invitation(OWNER_DONE, "ivan@company.local", Instant.parse("2026-10-19T10:00:00Z")),
                invitation(OWNER_IDLE, "anna@company.local", Instant.parse("2026-10-19T09:00:00Z"))));
        when(auth.findById(OWNER_DONE)).thenReturn(Optional.of(owner(OWNER_DONE, "ivan@company.local", 5L << 30)));
        when(auth.findById(OWNER_IDLE)).thenReturn(Optional.of(owner(OWNER_IDLE, "anna@company.local", 5L << 30)));
        when(onboarding.findById(any())).thenReturn(Optional.empty());
        when(protocol.usage(anyList())).thenReturn(List.of(
                new ProtocolDtos.MailboxUsageView(OWNER_DONE, 700, 12)));
    }

    @Test
    void migratedMessagesAreSummedFromFolderRows() {
        baseInvitations();
        MailMigrationJob done = job(OWNER_DONE, MailMigrationJob.Status.DONE);
        when(jobs.findAll()).thenReturn(List.of(done));
        when(jobs.findTop20ByOwnerIdOrderByCreatedAtDesc(OWNER_DONE)).thenReturn(List.of(done));
        when(jobs.findTop20ByOwnerIdOrderByCreatedAtDesc(OWNER_IDLE)).thenReturn(List.of());
        when(folders.findByJob_IdIn(List.of(done.getId()))).thenReturn(List.of(
                folder(done, "INBOX", 100), folder(done, "Archive", 20)));

        AdminMigrationDtos.Summary summary = service.summary(ADMIN, Set.of("ADMIN_MAIL_POLICY"));

        AdminMigrationDtos.Row row = summary.rows().get(0);
        assertEquals(OWNER_DONE, row.ownerId());
        assertEquals(120, row.migratedMessages(), "счётчик берётся из папок задания");
        assertEquals(done.getId(), row.jobId());
        assertEquals("DONE", row.jobStatus());
        assertEquals(5L << 30, row.quotaBytes());
        assertEquals(700, row.usedBytes());

        AdminMigrationDtos.Row idle = summary.rows().get(1);
        assertEquals(OWNER_IDLE, idle.ownerId());
        assertEquals(0, idle.migratedMessages());
        assertNull(idle.jobId());
        assertNull(idle.jobStatus());

        AdminMigrationDtos.Totals totals = summary.totals();
        assertEquals(2, totals.employees());
        assertEquals(2, totals.invited());
        assertEquals(1, totals.notStarted());
        assertEquals(0, totals.running());
        assertEquals(1, totals.done());
        assertEquals(0, totals.failed());
        assertEquals(120, totals.migratedMessages());
    }

    @Test
    void runningJobCountsAsRunningAndAuthOutageFallsBackToInvitation() {
        MailMigrationInvitation only = invitation(OWNER_DONE, "ivan@company.local", Instant.parse("2026-10-19T10:00:00Z"));
        when(invitations.findAll()).thenReturn(List.of(only));
        MailMigrationJob running = job(OWNER_DONE, MailMigrationJob.Status.RUNNING);
        when(jobs.findAll()).thenReturn(List.of(running));
        when(jobs.findTop20ByOwnerIdOrderByCreatedAtDesc(OWNER_DONE)).thenReturn(List.of(running));
        when(folders.findByJob_IdIn(List.of(running.getId()))).thenReturn(List.of(folder(running, "INBOX", 5)));
        when(auth.findById(OWNER_DONE)).thenThrow(new IllegalStateException("auth down"));
        when(onboarding.findById(any())).thenReturn(Optional.empty());
        when(protocol.usage(anyList())).thenThrow(new IllegalStateException("storage down"));

        AdminMigrationDtos.Summary summary = service.summary(ADMIN, Set.of("ADMIN_MAIL_POLICY"));

        AdminMigrationDtos.Row row = summary.rows().get(0);
        assertEquals("ivan@company.local", row.email(), "адрес из приглашения, когда auth-service недоступен");
        assertEquals(0, row.usedBytes(), "недоступный счётчик места не ломает сводку целиком");
        assertEquals(1, summary.totals().running());
        assertEquals(5, summary.totals().migratedMessages());
    }

    @Test
    void summaryRequiresTheMailPolicyPermission() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.summary(ADMIN, Set.of("ADMIN_USERS_MANAGE")));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }
}
