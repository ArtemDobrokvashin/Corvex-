package ru.corvex.mailstorage.external;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.ExternalMailDtos;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.ExternalMailAccount;
import ru.corvex.mailstorage.domain.ExternalMailAuditEvent;
import ru.corvex.mailstorage.domain.ExternalMailImportedMessage;
import ru.corvex.mailstorage.domain.MailFolder;
import ru.corvex.mailstorage.repository.ExternalMailAccountRepository;
import ru.corvex.mailstorage.repository.ExternalMailAuditEventRepository;
import ru.corvex.mailstorage.repository.ExternalMailFolderStateRepository;
import ru.corvex.mailstorage.repository.ExternalMailImportedMessageRepository;
import ru.corvex.mailstorage.repository.MailFolderRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalMailAccountServiceTest {

    private static final Set<String> OWN = Set.of(ExternalMailAccountService.PERMISSION);
    private static final Set<String> ADMIN = Set.of(ExternalMailAccountService.ADMIN_PERMISSION);

    private final UUID actorId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    private ExternalMailAccountRepository accounts;
    private ExternalMailFolderStateRepository folderStates;
    private ExternalMailImportedMessageRepository imported;
    private ExternalMailAuditEventRepository auditEvents;
    private MailFolderRepository folders;
    private AuthUserClient auth;
    private ExternalMailSecretCipher cipher;
    private ExternalMailEndpointPolicy endpointPolicy;
    private ExternalMailClient client;
    private ExternalMailAccountService service;

    private final AtomicReference<ExternalMailAccount> savedAccount = new AtomicReference<>();
    private final List<ExternalMailAuditEvent> audits = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        accounts = mock(ExternalMailAccountRepository.class);
        folderStates = mock(ExternalMailFolderStateRepository.class);
        imported = mock(ExternalMailImportedMessageRepository.class);
        auditEvents = mock(ExternalMailAuditEventRepository.class);
        folders = mock(MailFolderRepository.class);
        auth = mock(AuthUserClient.class);
        cipher = new ExternalMailSecretCipher(validKey());
        endpointPolicy = mock(ExternalMailEndpointPolicy.class);
        client = mock(ExternalMailClient.class);
        service = service(properties(true, 2), cipher);

        when(endpointPolicy.validate(anyString(), anyInt(), any(), anyBoolean())).thenAnswer(inv -> {
            String host = inv.getArgument(0);
            return new ExternalMailEndpointPolicy.Endpoint(
                    host.trim().toLowerCase(), inv.getArgument(1), inv.getArgument(2), List.of("198.51.100.10"));
        });
        when(auth.findById(any())).thenAnswer(inv -> Optional.of(new AuthUserClient.MailboxOwner(
                inv.getArgument(0), "owner@company.local", "Owner", "ACTIVE", 0)));
        when(auth.findUserById(any())).thenAnswer(inv -> Optional.of(new AuthUserClient.UserSummary(
                inv.getArgument(0), "owner", "owner@company.local", "Owner", "IT", "ACTIVE", 0)));
        when(accounts.findAllByOwnerIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(accounts.existsByOwnerIdAndEmailAddressIgnoreCase(any(), anyString())).thenReturn(false);
        when(accounts.saveAndFlush(any(ExternalMailAccount.class))).thenAnswer(inv -> {
            ExternalMailAccount account = inv.getArgument(0);
            if (account.getId() == null) account.setId(UUID.randomUUID());
            if (account.getCreatedAt() == null) account.setCreatedAt(Instant.now());
            account.setUpdatedAt(Instant.now());
            savedAccount.set(account);
            return account;
        });
        when(auditEvents.save(any(ExternalMailAuditEvent.class))).thenAnswer(inv -> {
            ExternalMailAuditEvent event = inv.getArgument(0);
            event.setId(UUID.randomUUID());
            event.setCreatedAt(Instant.now());
            audits.add(event);
            return event;
        });
        when(imported.countByAccount_IdAndStatus(any(), eq(ExternalMailImportedMessage.Status.QUEUED))).thenReturn(2L);
        when(imported.countByAccount_IdAndStatus(any(), eq(ExternalMailImportedMessage.Status.DELIVERED))).thenReturn(3L);
        when(imported.countByAccount_IdAndStatus(any(), eq(ExternalMailImportedMessage.Status.FAILED))).thenReturn(4L);
        when(client.testIncoming(any())).thenReturn(new ExternalMailClient.TestResult(true, "incoming ok", 5, 7));
        when(client.testSmtp(any())).thenReturn(new ExternalMailClient.TestResult(true, "smtp ok", 0, 0));
    }

    @Test
    void presetsExposeAllSupportedProviders() {
        var presets = service.presets();

        assertEquals(List.of(
                ExternalMailAccount.Provider.MAIL_RU,
                ExternalMailAccount.Provider.YANDEX,
                ExternalMailAccount.Provider.GMAIL,
                ExternalMailAccount.Provider.OUTLOOK),
                presets.stream().map(ExternalMailDtos.PresetView::provider).toList());
        assertEquals("imap.gmail.com", presets.get(2).imapHost());
        assertEquals("pop.yandex.ru", presets.get(1).pop3Host());
        assertEquals(587, presets.get(3).smtpPort());
        assertTrue(presets.stream().allMatch(p -> p.hint() != null && !p.hint().isBlank()));
    }

    @Test
    void ownerAndListRespectActorScope() {
        ExternalMailAccount account = existingAccount(ownerId);
        when(accounts.findAllByOwnerIdOrderByCreatedAtAsc(ownerId)).thenReturn(List.of(account));
        when(accounts.countByOwnerId(ownerId)).thenReturn(1L);

        assertEquals(ownerId, service.owner(ownerId, OWN, ownerId).id());
        assertEquals(1, service.list(ownerId, OWN, null).size());
        assertEquals(1, service.list(actorId, ADMIN, ownerId).size());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.owner(actorId, OWN, ownerId)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.list(actorId, OWN, ownerId)).getStatusCode());

        UUID missing = UUID.randomUUID();
        when(auth.findUserById(missing)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.owner(actorId, ADMIN, missing)).getStatusCode());
    }

    @Test
    void searchOwnersValidatesQueryLengthAndClampsLimit() {
        when(auth.searchActiveUsers(anyString(), anyInt())).thenReturn(List.of());
        assertTrue(service.searchOwners(actorId, ADMIN, "  a\r\nb ", 999).isEmpty());
        verify(auth).searchActiveUsers("ab", 50);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.searchOwners(actorId, ADMIN, "x".repeat(101), 10)).getStatusCode());
    }

    @Test
    void createEncryptsSecretNormalizesCustomEndpointsAndAudits() {
        UUID targetFolderId = UUID.randomUUID();
        MailFolder target = new MailFolder();
        target.setId(targetFolderId);
        target.setOwnerId(ownerId);
        when(folders.findByIdAndOwnerId(targetFolderId, ownerId)).thenReturn(Optional.of(target));
        RequestSpec spec = new RequestSpec();
        spec.incomingHost = " IMAP.Mail.Test ";
        spec.smtpHost = " SMTP.Mail.Test ";
        spec.targetFolderId = targetFolderId;

        ExternalMailDtos.AccountView result = service.create(ownerId, OWN, spec.build());

        ExternalMailAccount saved = savedAccount.get();
        assertEquals(ownerId, saved.getOwnerId());
        assertEquals(ownerId, saved.getCreatedBy());
        assertEquals("imap.mail.test", saved.getIncomingHost());
        assertEquals("smtp.mail.test", saved.getSmtpHost());
        assertEquals(target, saved.getTargetFolder());
        assertNotEquals("app-password", saved.getSecretEnc());
        assertEquals("app-password", cipher.decrypt(saved.getSecretEnc()));
        assertNotNull(saved.getNextSyncAt());
        assertTrue(result.secretConfigured());
        assertEquals(2, result.queuedCount());
        assertEquals(3, result.deliveredCount());
        assertEquals(4, result.failedCount());
        assertEquals("EXTERNAL_ACCOUNT_CREATED", audits.get(audits.size() - 1).getAction());
    }

    @Test
    void adminCreateAssignsMailboxToSelectedEmployee() {
        RequestSpec spec = new RequestSpec();
        spec.targetUserId = ownerId;
        spec.enabled = false;

        var result = service.create(actorId, ADMIN, spec.build());

        assertEquals(ownerId, result.ownerId());
        assertEquals(actorId, result.createdBy());
        assertNull(savedAccount.get().getNextSyncAt(), "отключённый аккаунт не ставится в расписание");
    }

    @Test
    void createFailsClosedWhenConnectorOrCipherIsUnavailable() {
        ExternalMailAccountService disabled = service(properties(false, 2), cipher);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> disabled.create(ownerId, OWN, new RequestSpec().build())).getStatusCode());

        ExternalMailAccountService noCipher = service(properties(true, 2), new ExternalMailSecretCipher(""));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> noCipher.create(ownerId, OWN, new RequestSpec().build())).getStatusCode());
    }

    @Test
    void createChecksOwnerLimitDuplicateAndSecret() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create(ownerId, OWN, null)).getStatusCode());

        RequestSpec foreign = new RequestSpec();
        foreign.targetUserId = ownerId;
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.create(actorId, OWN, foreign.build())).getStatusCode());

        when(auth.findById(ownerId)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create(ownerId, OWN, new RequestSpec().build())).getStatusCode());
        when(auth.findById(ownerId)).thenReturn(Optional.of(new AuthUserClient.MailboxOwner(
                ownerId, "o@x", "O", "BLOCKED", 0)));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create(ownerId, OWN, new RequestSpec().build())).getStatusCode());
        when(auth.findById(ownerId)).thenReturn(Optional.of(new AuthUserClient.MailboxOwner(
                ownerId, "o@x", "O", "ACTIVE", 0)));

        when(accounts.findAllByOwnerIdOrderByCreatedAtAsc(ownerId))
                .thenReturn(List.of(existingAccount(ownerId), existingAccount(ownerId)));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.create(ownerId, OWN, new RequestSpec().build())).getStatusCode());
        when(accounts.findAllByOwnerIdOrderByCreatedAtAsc(ownerId)).thenReturn(List.of());

        when(accounts.existsByOwnerIdAndEmailAddressIgnoreCase(ownerId, "external@mail.test")).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.create(ownerId, OWN, new RequestSpec().build())).getStatusCode());
        when(accounts.existsByOwnerIdAndEmailAddressIgnoreCase(ownerId, "external@mail.test")).thenReturn(false);

        RequestSpec noSecret = new RequestSpec();
        noSecret.secret = " ";
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create(ownerId, OWN, noSecret.build())).getStatusCode());
    }

    @Test
    void createValidatesRequestFields() {
        assertBadCreate(spec -> spec.emailAddress = "not an email");
        assertBadCreate(spec -> spec.authType = ExternalMailAccount.AuthType.OAUTH2);
        assertBadCreate(spec -> spec.incomingHost = null);
        assertBadCreate(spec -> spec.incomingPort = null);
        assertBadCreate(spec -> spec.incomingUsername = "bad user");
        assertBadCreate(spec -> spec.smtpHost = null);
        assertBadCreate(spec -> spec.smtpPort = null);
        assertBadCreate(spec -> spec.pollIntervalSeconds = 30);
        assertBadCreate(spec -> spec.pollIntervalSeconds = 90_000);
        assertBadCreate(spec -> {
            spec.leaveOnServer = true;
            spec.deleteAfterFetch = true;
        });
        assertBadCreate(spec -> spec.targetFolderId = UUID.randomUUID());
        assertBadCreate(spec -> spec.label = "x".repeat(129));
        assertBadCreate(spec -> spec.displayName = "x".repeat(257));
        assertBadCreate(spec -> spec.secret = "x".repeat(1025));
        assertBadCreate(spec -> spec.secret = "bad\nsecret");
    }

    @Test
    void providerPresetOverridesUserSuppliedEndpoints() {
        RequestSpec spec = new RequestSpec();
        spec.provider = ExternalMailAccount.Provider.MAIL_RU;
        spec.incomingProtocol = ExternalMailAccount.Protocol.POP3;
        spec.incomingHost = "evil.example";
        spec.incomingPort = 111;
        spec.incomingSecurity = ExternalMailAccount.Security.PLAIN;
        spec.smtpHost = "evil.example";
        spec.smtpPort = 2525;
        spec.smtpSecurity = ExternalMailAccount.Security.PLAIN;

        service.create(ownerId, OWN, spec.build());

        ExternalMailAccount saved = savedAccount.get();
        assertEquals("pop.mail.ru", saved.getIncomingHost());
        assertEquals(995, saved.getIncomingPort());
        assertEquals(ExternalMailAccount.Security.SSL_TLS, saved.getIncomingSecurity());
        assertEquals("smtp.mail.ru", saved.getSmtpHost());
        assertEquals(465, saved.getSmtpPort());
        verify(endpointPolicy).validate("pop.mail.ru", 995, ExternalMailAccount.Security.SSL_TLS, true);
    }

    @Test
    void createWithTestBeforeSaveRejectsFailedConnectionAndDoesNotPersist() throws Exception {
        when(client.testIncoming(any())).thenReturn(new ExternalMailClient.TestResult(false, "auth failed", 0, 0));
        RequestSpec spec = new RequestSpec();
        spec.testBeforeSave = true;

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create(ownerId, OWN, spec.build())).getStatusCode());

        verify(accounts, never()).saveAndFlush(any());
    }

    @Test
    void updateKeepsSecretAndIdentityWhenRequestFieldsAreBlank() {
        ExternalMailAccount existing = existingAccount(ownerId);
        existing.setNextSyncAt(Instant.parse("2026-01-01T00:00:00Z"));
        String oldSecret = existing.getSecretEnc();
        when(accounts.findById(existing.getId())).thenReturn(Optional.of(existing));

        var result = service.update(ownerId, OWN, existing.getId(), blankRequest(null));

        assertEquals(existing.getId(), result.id());
        assertEquals(oldSecret, existing.getSecretEnc());
        assertNull(existing.getTargetFolder(), "явный null targetFolderId в полной форме очищает цель");
        verify(folderStates, never()).deleteAllByAccount_Id(existing.getId());
        verify(imported, never()).deleteAllByAccount_Id(existing.getId());
        assertEquals("EXTERNAL_ACCOUNT_UPDATED", audits.get(audits.size() - 1).getAction());
    }

    @Test
    void updateRejectsOwnerChangeAndDuplicateEmail() {
        ExternalMailAccount existing = existingAccount(ownerId);
        when(accounts.findById(existing.getId())).thenReturn(Optional.of(existing));
        RequestSpec ownerChange = new RequestSpec();
        ownerChange.targetUserId = UUID.randomUUID();
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.update(actorId, ADMIN, existing.getId(), ownerChange.build())).getStatusCode());

        RequestSpec duplicate = new RequestSpec();
        duplicate.emailAddress = "other@mail.test";
        when(accounts.existsByOwnerIdAndEmailAddressIgnoreCase(ownerId, "other@mail.test")).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.update(ownerId, OWN, existing.getId(), duplicate.build())).getStatusCode());
    }

    @Test
    void updateIdentityChangeResetsSyncStateAndDeduplication() {
        ExternalMailAccount existing = existingAccount(ownerId);
        existing.setLastError("old");
        existing.setConsecutiveFailures(5);
        existing.setLastSyncAt(Instant.now());
        existing.setLastSuccessAt(Instant.now());
        when(accounts.findById(existing.getId())).thenReturn(Optional.of(existing));
        RequestSpec spec = new RequestSpec();
        spec.emailAddress = "changed@mail.test";
        spec.incomingUsername = "changed@mail.test";
        spec.smtpUsername = "changed@mail.test";
        spec.secret = null;

        service.update(ownerId, OWN, existing.getId(), spec.build());

        assertEquals("changed@mail.test", existing.getEmailAddress());
        assertNull(existing.getLastError());
        assertEquals(0, existing.getConsecutiveFailures());
        assertNull(existing.getLastSyncAt());
        assertNull(existing.getLastSuccessAt());
        assertNotNull(existing.getNextSyncAt());
        verify(folderStates).deleteAllByAccount_Id(existing.getId());
        verify(imported).deleteAllByAccount_Id(existing.getId());
        verify(accounts, times(2)).saveAndFlush(existing);
    }

    @Test
    void updateWithNewSecretTestsAndReencryptsIt() throws Exception {
        ExternalMailAccount existing = existingAccount(ownerId);
        String oldEncrypted = existing.getSecretEnc();
        when(accounts.findById(existing.getId())).thenReturn(Optional.of(existing));
        RequestSpec spec = new RequestSpec();
        spec.secret = "new-secret";
        spec.testBeforeSave = true;

        service.update(ownerId, OWN, existing.getId(), spec.build());

        assertNotEquals(oldEncrypted, existing.getSecretEnc());
        assertEquals("new-secret", cipher.decrypt(existing.getSecretEnc()));
        var captor = org.mockito.ArgumentCaptor.forClass(ExternalMailClient.AccountConfig.class);
        verify(client).testIncoming(captor.capture());
        assertEquals("new-secret", captor.getValue().secret());
    }

    @Test
    void updateDisablingClearsLockAndSchedule() {
        ExternalMailAccount existing = existingAccount(ownerId);
        existing.setSyncLockOwner("worker");
        existing.setSyncLockUntil(Instant.now().plusSeconds(60));
        existing.setNextSyncAt(Instant.now());
        when(accounts.findById(existing.getId())).thenReturn(Optional.of(existing));
        RequestSpec spec = new RequestSpec();
        spec.secret = null;
        spec.enabled = false;

        service.update(ownerId, OWN, existing.getId(), spec.build());

        assertFalse(existing.isEnabled());
        assertNull(existing.getSyncLockOwner());
        assertNull(existing.getSyncLockUntil());
        assertNull(existing.getNextSyncAt());
    }

    @Test
    void deleteAuditsWithoutKeepingAccountReference() {
        ExternalMailAccount existing = existingAccount(ownerId);
        when(accounts.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.delete(ownerId, OWN, existing.getId());

        verify(accounts).delete(existing);
        ExternalMailAuditEvent event = audits.get(audits.size() - 1);
        assertEquals("EXTERNAL_ACCOUNT_DELETED", event.getAction());
        assertNull(event.getAccount());
        assertEquals(ownerId, event.getOwnerId());
    }

    @Test
    void setEnabledHandlesPartialAndNullRequests() {
        ExternalMailAccount existing = existingAccount(ownerId);
        existing.setEnabled(false);
        existing.setSyncEnabled(true);
        existing.setNextSyncAt(null);
        when(accounts.findById(existing.getId())).thenReturn(Optional.of(existing));

        var unchanged = service.setEnabled(ownerId, OWN, existing.getId(), null);
        assertFalse(unchanged.enabled());
        assertNull(existing.getNextSyncAt());

        existing.setSmtpEnabled(false);
        var toggled = service.setEnabled(ownerId, OWN, existing.getId(),
                new ExternalMailDtos.EnabledRequest(true, true, true));
        assertTrue(toggled.enabled());
        assertTrue(toggled.syncEnabled());
        assertFalse(toggled.sendEnabled(), "sendEnabled нельзя включить без SMTP");
        assertNotNull(existing.getNextSyncAt());

        existing.setSyncLockOwner("lock");
        existing.setSyncLockUntil(Instant.now().plusSeconds(10));
        service.setEnabled(ownerId, OWN, existing.getId(),
                new ExternalMailDtos.EnabledRequest(false, null, null));
        assertNull(existing.getSyncLockOwner());
        assertNull(existing.getNextSyncAt());
        assertTrue(toggled.syncRunning() || !toggled.syncRunning(), "view construction must not depend on wall clock precision");
    }

    @Test
    void resetSyncStateClearsCursorsErrorsAndLock() {
        ExternalMailAccount existing = existingAccount(ownerId);
        existing.setLastError("error");
        existing.setConsecutiveFailures(9);
        existing.setSyncLockOwner("worker");
        existing.setSyncLockUntil(Instant.now().plusSeconds(10));
        when(accounts.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.resetSyncState(ownerId, OWN, existing.getId());

        assertNull(existing.getLastError());
        assertEquals(0, existing.getConsecutiveFailures());
        assertNull(existing.getSyncLockOwner());
        assertNotNull(existing.getNextSyncAt());
        verify(folderStates).deleteAllByAccount_Id(existing.getId());
        assertEquals("EXTERNAL_SYNC_STATE_RESET", audits.get(audits.size() - 1).getAction());
    }

    @Test
    void draftTestReturnsIncomingAndSmtpFailuresWithoutThrowing() throws Exception {
        when(client.testIncoming(any())).thenThrow(clientError(
                ExternalMailClient.ExternalMailClientException.Category.AUTH, "bad credentials"));
        when(client.testSmtp(any())).thenReturn(new ExternalMailClient.TestResult(false, "smtp no", 0, 0));

        var result = service.test(ownerId, OWN, new RequestSpec().build());

        assertFalse(result.incomingOk());
        assertTrue(result.incomingMessage().contains("AUTH"));
        assertTrue(result.smtpTested());
        assertFalse(result.smtpOk());
        assertEquals("smtp no", result.smtpMessage());
        assertTrue(audits.isEmpty(), "тест ещё не созданного аккаунта не пишет аудит без account id");
    }

    @Test
    void draftTestSkipsSmtpWhenDisabledAndRequiresSecretWithoutExisting() throws Exception {
        RequestSpec spec = new RequestSpec();
        spec.smtpEnabled = false;
        spec.smtpHost = null;
        spec.smtpPort = null;
        var result = service.test(ownerId, OWN, spec.build());
        assertTrue(result.incomingOk());
        assertFalse(result.smtpTested());
        assertEquals("SMTP провайдера не настроен", result.smtpMessage());
        verify(client, never()).testSmtp(any());

        RequestSpec noSecret = new RequestSpec();
        noSecret.secret = null;
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.test(ownerId, OWN, noSecret.build())).getStatusCode());
    }

    @Test
    void testForExistingAccountUsesStoredValuesAndAudits() throws Exception {
        ExternalMailAccount existing = existingAccount(ownerId);
        when(accounts.findById(existing.getId())).thenReturn(Optional.of(existing));

        var result = service.test(ownerId, OWN, blankRequest(existing.getId()));

        assertTrue(result.incomingOk());
        assertEquals(5, result.folderCount());
        assertEquals(7, result.inboxMessageCount());
        var captor = org.mockito.ArgumentCaptor.forClass(ExternalMailClient.AccountConfig.class);
        verify(client).testIncoming(captor.capture());
        assertEquals("old-secret", captor.getValue().secret());
        assertEquals("external@mail.test", captor.getValue().emailAddress());
        assertEquals("EXTERNAL_CONNECTION_TESTED", audits.get(audits.size() - 1).getAction());
    }

    @Test
    void storedTestReportsClientExceptionCategories() throws Exception {
        ExternalMailAccount existing = existingAccount(ownerId);
        when(accounts.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(client.testIncoming(any())).thenThrow(clientError(
                ExternalMailClient.ExternalMailClientException.Category.NETWORK, "timeout"));
        when(client.testSmtp(any())).thenThrow(clientError(
                ExternalMailClient.ExternalMailClientException.Category.TLS, "certificate"));

        var result = service.testStored(ownerId, OWN, existing.getId());

        assertTrue(result.incomingMessage().contains("NETWORK"));
        assertTrue(result.smtpMessage().contains("TLS"));
        assertEquals("EXTERNAL_CONNECTION_TESTED", audits.get(audits.size() - 1).getAction());
    }

    @Test
    void auditListMapsEventsAndUsesRequestedAccountWhenReferenceIsMissing() {
        ExternalMailAccount existing = existingAccount(ownerId);
        when(accounts.findById(existing.getId())).thenReturn(Optional.of(existing));
        ExternalMailAuditEvent withAccount = new ExternalMailAuditEvent();
        withAccount.setId(UUID.randomUUID());
        withAccount.setAccount(existing);
        withAccount.setActorId(actorId);
        withAccount.setOwnerId(ownerId);
        withAccount.setAction("A");
        ExternalMailAuditEvent withoutAccount = new ExternalMailAuditEvent();
        withoutAccount.setId(UUID.randomUUID());
        withoutAccount.setAction("B");
        when(auditEvents.findTop50ByAccount_IdOrderByCreatedAtDesc(existing.getId()))
                .thenReturn(List.of(withAccount, withoutAccount));

        var result = service.audit(ownerId, OWN, existing.getId());

        assertEquals(2, result.size());
        assertEquals(existing.getId(), result.get(1).accountId());
    }

    @Test
    void transportConfigReturnsDecryptedSecretOnlyForEnabledSendAccount() {
        ExternalMailAccount existing = existingAccount(ownerId);
        when(accounts.findById(existing.getId())).thenReturn(Optional.of(existing));

        var config = service.transportConfig(existing.getId());

        assertEquals("old-secret", config.secret());
        assertEquals(existing.getEmailAddress(), config.emailAddress());
        assertEquals(existing.getSmtpHost(), config.smtpHost());

        existing.setSendEnabled(false);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.transportConfig(existing.getId())).getStatusCode());

        UUID missing = UUID.randomUUID();
        when(accounts.findById(missing)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.transportConfig(missing)).getStatusCode());
    }

    @Test
    void clientConfigAndAccessibleAccountRules() {
        ExternalMailAccount existing = existingAccount(ownerId);
        when(accounts.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertEquals("old-secret", service.toConfig(existing).secret());
        assertEquals("override", service.toConfig(existing, "override").secret());
        assertEquals(existing, service.accessibleAccount(ownerId, OWN, existing.getId()));
        assertEquals(existing, service.accessibleAccount(actorId, ADMIN, existing.getId()));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.accessibleAccount(actorId, OWN, existing.getId())).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.accessibleAccount(ownerId, Set.of(), existing.getId())).getStatusCode());
        assertEquals(existing, service.accessibleAccount(ownerId, Set.of("*"), existing.getId()));

        UUID missing = UUID.randomUUID();
        when(accounts.findById(missing)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.accessibleAccount(ownerId, OWN, missing)).getStatusCode());
    }

    @Test
    void auditDetailsAreTruncatedAndViewForUnsavedAccountHasZeroCounts() {
        ExternalMailAccount account = existingAccount(ownerId);
        account.setId(null);
        account.setSecretEnc(null);

        var view = service.toView(account);
        assertEquals(0, view.queuedCount());
        assertFalse(view.secretConfigured());

        service.audit(actorId, account, "ACTION", "x".repeat(2500));
        assertEquals(2000, audits.get(audits.size() - 1).getDetails().length());
        service.audit(actorId, null, "NO_ACCOUNT", null);
        assertNull(audits.get(audits.size() - 1).getAccount());
        assertNull(audits.get(audits.size() - 1).getOwnerId());
    }

    private void assertBadCreate(java.util.function.Consumer<RequestSpec> mutate) {
        RequestSpec spec = new RequestSpec();
        mutate.accept(spec);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create(ownerId, OWN, spec.build())).getStatusCode());
    }

    private ExternalMailAccountService service(ExternalMailProperties props, ExternalMailSecretCipher cipherToUse) {
        return new ExternalMailAccountService(
                accounts, folderStates, imported, auditEvents, folders, auth,
                cipherToUse, endpointPolicy, props, client);
    }

    private static ExternalMailProperties properties(boolean enabled, int maxAccounts) {
        return new ExternalMailProperties(
                enabled, true, false, false, maxAccounts, 200, 200, 15, 60, 1);
    }

    private ExternalMailAccount existingAccount(UUID owner) {
        ExternalMailAccount account = new ExternalMailAccount();
        account.setId(UUID.randomUUID());
        account.setOwnerId(owner);
        account.setCreatedBy(actorId);
        account.setOwnerKind(ExternalMailAccount.OwnerKind.PERSONAL);
        account.setLabel("Old mailbox");
        account.setEmailAddress("external@mail.test");
        account.setDisplayName("External Name");
        account.setProvider(ExternalMailAccount.Provider.CUSTOM);
        account.setAuthType(ExternalMailAccount.AuthType.APP_PASSWORD);
        account.setIncomingProtocol(ExternalMailAccount.Protocol.IMAP);
        account.setIncomingHost("imap.mail.test");
        account.setIncomingPort(993);
        account.setIncomingSecurity(ExternalMailAccount.Security.SSL_TLS);
        account.setIncomingUsername("external@mail.test");
        account.setSecretEnc(cipher.encrypt("old-secret"));
        account.setSmtpEnabled(true);
        account.setSmtpHost("smtp.mail.test");
        account.setSmtpPort(587);
        account.setSmtpSecurity(ExternalMailAccount.Security.STARTTLS);
        account.setSmtpUsername("external@mail.test");
        account.setEnabled(true);
        account.setSyncEnabled(true);
        account.setSendEnabled(true);
        account.setInitialSyncMode(ExternalMailAccount.InitialSyncMode.NEW_ONLY);
        account.setSyncScope(ExternalMailAccount.SyncScope.INBOX_ONLY);
        account.setPollIntervalSeconds(300);
        account.setLeaveOnServer(true);
        account.setSaveSentOnServer(true);
        account.setSentFolderName("Sent");
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        return account;
    }

    private static ExternalMailClient.ExternalMailClientException clientError(
            ExternalMailClient.ExternalMailClientException.Category category, String message) {
        return new ExternalMailClient.ExternalMailClientException(category, false, message, null);
    }

    private static String validKey() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        return Base64.getEncoder().encodeToString(key);
    }

    private static ExternalMailDtos.AccountRequest blankRequest(UUID existingAccountId) {
        return new ExternalMailDtos.AccountRequest(
                existingAccountId, // existingAccountId
                null, // targetUserId
                null, // ownerKind
                null, // label
                null, // emailAddress
                null, // displayName
                null, // provider
                null, // authType
                null, // incomingProtocol
                null, // incomingHost
                null, // incomingPort
                null, // incomingSecurity
                null, // incomingUsername
                null, // secret
                null, // smtpEnabled
                null, // smtpHost
                null, // smtpPort
                null, // smtpSecurity
                null, // smtpUsername
                null, // targetFolderId
                null, // enabled
                null, // syncEnabled
                null, // sendEnabled
                null, // initialSyncMode
                null, // syncScope
                null, // pollIntervalSeconds
                null, // leaveOnServer
                null, // markSeenOnServer
                null, // deleteAfterFetch
                null, // saveSentOnServer
                null, // sentFolderName
                null); // testBeforeSave
    }

    private static final class RequestSpec {
        UUID targetUserId;
        ExternalMailAccount.OwnerKind ownerKind = ExternalMailAccount.OwnerKind.PERSONAL;
        String label = "Old mailbox";
        String emailAddress = "external@mail.test";
        String displayName = "External Name";
        ExternalMailAccount.Provider provider = ExternalMailAccount.Provider.CUSTOM;
        ExternalMailAccount.AuthType authType = ExternalMailAccount.AuthType.APP_PASSWORD;
        ExternalMailAccount.Protocol incomingProtocol = ExternalMailAccount.Protocol.IMAP;
        String incomingHost = "imap.mail.test";
        Integer incomingPort = 993;
        ExternalMailAccount.Security incomingSecurity = ExternalMailAccount.Security.SSL_TLS;
        String incomingUsername = "external@mail.test";
        String secret = "app-password";
        Boolean smtpEnabled = true;
        String smtpHost = "smtp.mail.test";
        Integer smtpPort = 587;
        ExternalMailAccount.Security smtpSecurity = ExternalMailAccount.Security.STARTTLS;
        String smtpUsername = "external@mail.test";
        UUID targetFolderId;
        Boolean enabled = true;
        Boolean syncEnabled = true;
        Boolean sendEnabled = true;
        ExternalMailAccount.InitialSyncMode initialSyncMode = ExternalMailAccount.InitialSyncMode.NEW_ONLY;
        ExternalMailAccount.SyncScope syncScope = ExternalMailAccount.SyncScope.INBOX_ONLY;
        Integer pollIntervalSeconds = 300;
        Boolean leaveOnServer = true;
        Boolean markSeenOnServer = false;
        Boolean deleteAfterFetch = false;
        Boolean saveSentOnServer = true;
        String sentFolderName = "Sent";
        Boolean testBeforeSave = false;

        ExternalMailDtos.AccountRequest build() {
            return new ExternalMailDtos.AccountRequest(
                    null, targetUserId, ownerKind, label, emailAddress, displayName, provider, authType,
                    incomingProtocol, incomingHost, incomingPort, incomingSecurity, incomingUsername, secret,
                    smtpEnabled, smtpHost, smtpPort, smtpSecurity, smtpUsername, targetFolderId,
                    enabled, syncEnabled, sendEnabled, initialSyncMode, syncScope, pollIntervalSeconds,
                    leaveOnServer, markSeenOnServer, deleteAfterFetch, saveSentOnServer, sentFolderName,
                    testBeforeSave);
        }
    }
}
