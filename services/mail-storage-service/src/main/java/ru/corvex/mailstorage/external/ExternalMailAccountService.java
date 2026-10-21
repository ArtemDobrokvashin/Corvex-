package ru.corvex.mailstorage.external;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD и проверка внешних ящиков. Владелец аккаунта — пользователь Corvex, в чей ящик
 * доставляется собранная почта. Администратор с ADMIN_MAIL_POLICY может подключить личный
 * внешний ящик сотруднику или shared legacy-ящик отдельному сервисному пользователю Corvex.
 */
@Service
public class ExternalMailAccountService {

    public static final String PERMISSION = "MAIL_EXTERNAL_ACCOUNTS";
    public static final String ADMIN_PERMISSION = "ADMIN_MAIL_POLICY";

    private final ExternalMailAccountRepository accounts;
    private final ExternalMailFolderStateRepository folderStates;
    private final ExternalMailImportedMessageRepository imported;
    private final ExternalMailAuditEventRepository auditEvents;
    private final MailFolderRepository folders;
    private final AuthUserClient auth;
    private final ExternalMailSecretCipher cipher;
    private final ExternalMailEndpointPolicy endpointPolicy;
    private final ExternalMailProperties props;
    private final ExternalMailClient client;

    public ExternalMailAccountService(ExternalMailAccountRepository accounts,
                                      ExternalMailFolderStateRepository folderStates,
                                      ExternalMailImportedMessageRepository imported,
                                      ExternalMailAuditEventRepository auditEvents,
                                      MailFolderRepository folders,
                                      AuthUserClient auth,
                                      ExternalMailSecretCipher cipher,
                                      ExternalMailEndpointPolicy endpointPolicy,
                                      ExternalMailProperties props,
                                      ExternalMailClient client) {
        this.accounts = accounts;
        this.folderStates = folderStates;
        this.imported = imported;
        this.auditEvents = auditEvents;
        this.folders = folders;
        this.auth = auth;
        this.cipher = cipher;
        this.endpointPolicy = endpointPolicy;
        this.props = props;
        this.client = client;
    }

    public List<ExternalMailDtos.PresetView> presets() {
        return List.of(
                preset(ExternalMailAccount.Provider.MAIL_RU),
                preset(ExternalMailAccount.Provider.YANDEX),
                preset(ExternalMailAccount.Provider.GMAIL),
                preset(ExternalMailAccount.Provider.OUTLOOK));
    }

    /**
     * Поиск сотрудников для админского сценария «подключить ящик пользователю». Доступен только
     * ADMIN_MAIL_POLICY: обычный сотрудник не должен получать каталог компании через почтовый API.
     */
    @Transactional(readOnly = true)
    public List<ExternalMailDtos.OwnerView> searchOwners(UUID actorId, Set<String> authorities,
                                                         String query, Integer limit) {
        requireAdmin(authorities);
        String cleanQuery = query == null ? "" : query.replace("\r", "").replace("\n", "").trim();
        if (cleanQuery.length() > 100) {
            throw bad("Запрос поиска сотрудника слишком длинный");
        }
        int safeLimit = limit == null ? 25 : Math.max(1, Math.min(limit, 50));
        return auth.searchActiveUsers(cleanQuery, safeLimit).stream()
                .map(this::toOwnerView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ExternalMailDtos.OwnerView owner(UUID actorId, Set<String> authorities, UUID ownerId) {
        requireManage(actorId, ownerId, authorities);
        AuthUserClient.UserSummary owner = auth.findUserById(ownerId)
                .orElseThrow(() -> bad("Пользователь-владелец Corvex не найден"));
        return toOwnerView(owner);
    }

    @Transactional(readOnly = true)
    public List<ExternalMailDtos.TargetFolderView> ownerFolders(UUID actorId, Set<String> authorities, UUID ownerId) {
        requireManage(actorId, ownerId, authorities);
        auth.findUserById(ownerId)
                .orElseThrow(() -> bad("Пользователь-владелец Corvex не найден"));
        List<MailFolder> ownerFolders = folders.findAllByOwnerIdOrderBySpecialUseAscNameAsc(ownerId);
        Map<UUID, MailFolder> byId = new HashMap<>();
        for (MailFolder folder : ownerFolders) {
            byId.put(folder.getId(), folder);
        }
        return ownerFolders.stream()
                .map(folder -> new ExternalMailDtos.TargetFolderView(
                        folder.getId(),
                        folder.getName(),
                        folder.getSpecialUse(),
                        folder.getParent() == null ? null : folder.getParent().getId(),
                        folderPath(folder, byId)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExternalMailDtos.AccountView> list(UUID actorId, Set<String> authorities, UUID requestedOwnerId) {
        UUID ownerId = requestedOwnerId == null ? actorId : requestedOwnerId;
        requireManage(actorId, ownerId, authorities);
        return accounts.findAllByOwnerIdOrderByCreatedAtAsc(ownerId).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ExternalMailDtos.AccountView get(UUID actorId, Set<String> authorities, UUID accountId) {
        return toView(accessibleAccount(actorId, authorities, accountId));
    }

    @Transactional
    public ExternalMailDtos.AccountView create(UUID actorId, Set<String> authorities, ExternalMailDtos.AccountRequest req) {
        requireReady();
        if (req == null) throw bad("Заполните параметры внешнего ящика");
        UUID ownerId = req.targetUserId() == null ? actorId : req.targetUserId();
        requireManage(actorId, ownerId, authorities);
        requireActiveOwner(ownerId);
        if (accounts.findAllByOwnerIdOrderByCreatedAtAsc(ownerId).size() >= Math.max(1, props.maxAccountsPerUser())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Достигнут лимит внешних ящиков для пользователя");
        }
        String email = normalizeEmail(req.emailAddress());
        if (accounts.existsByOwnerIdAndEmailAddressIgnoreCase(ownerId, email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Этот внешний ящик уже подключён");
        }
        String secret = requireSecret(req.secret(), true);
        ExternalMailAccount account = new ExternalMailAccount();
        account.setOwnerId(ownerId);
        account.setCreatedBy(actorId);
        applyRequest(account, req, null, email, ownerId);
        account.setSecretEnc(cipher.encrypt(secret));
        if (Boolean.TRUE.equals(req.testBeforeSave())) {
            testOrThrow(account, secret);
        }
        account.setNextSyncAt(account.isEnabled() && account.isSyncEnabled() ? Instant.now() : null);
        ExternalMailAccount saved = accounts.saveAndFlush(account);
        audit(actorId, saved, "EXTERNAL_ACCOUNT_CREATED",
                "email=" + saved.getEmailAddress() + ", provider=" + saved.getProvider() + ", protocol=" + saved.getIncomingProtocol());
        return toView(saved);
    }

    @Transactional
    public ExternalMailDtos.AccountView update(UUID actorId, Set<String> authorities, UUID accountId,
                                                ExternalMailDtos.AccountRequest req) {
        requireReady();
        if (req == null) throw bad("Заполните параметры внешнего ящика");
        ExternalMailAccount account = accessibleAccount(actorId, authorities, accountId);
        if (req.targetUserId() != null && !req.targetUserId().equals(account.getOwnerId())) {
            throw bad("Владельца подключённого ящика изменить нельзя; удалите подключение и создайте новое");
        }
        String oldIdentity = sourceIdentity(account);
        String oldEmail = account.getEmailAddress();
        String email = req.emailAddress() == null || req.emailAddress().isBlank()
                ? oldEmail : normalizeEmail(req.emailAddress());
        if (!email.equalsIgnoreCase(oldEmail)
                && accounts.existsByOwnerIdAndEmailAddressIgnoreCase(account.getOwnerId(), email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Этот внешний ящик уже подключён");
        }
        boolean secretChanged = req.secret() != null && !req.secret().isBlank();
        String secret = secretChanged ? requireSecret(req.secret(), true) : cipher.decrypt(account.getSecretEnc());
        applyRequest(account, req, account, email, account.getOwnerId());
        if (secretChanged) {
            account.setSecretEnc(cipher.encrypt(secret));
        }
        if (Boolean.TRUE.equals(req.testBeforeSave())) {
            testOrThrow(account, secret);
        }
        if (account.isEnabled() && account.isSyncEnabled() && account.getNextSyncAt() == null) {
            account.setNextSyncAt(Instant.now());
        }
        if (!account.isEnabled()) {
            account.setSyncLockOwner(null);
            account.setSyncLockUntil(null);
            account.setNextSyncAt(null);
        }
        ExternalMailAccount saved = accounts.saveAndFlush(account);
        boolean identityChanged = !oldIdentity.equals(sourceIdentity(saved));
        if (identityChanged) {
            folderStates.deleteAllByAccount_Id(saved.getId());
            imported.deleteAllByAccount_Id(saved.getId());
            saved.setLastError(null);
            saved.setConsecutiveFailures(0);
            saved.setLastSyncAt(null);
            saved.setLastSuccessAt(null);
            saved.setNextSyncAt(saved.isEnabled() && saved.isSyncEnabled() ? Instant.now() : null);
            saved = accounts.saveAndFlush(saved);
        }
        audit(actorId, saved, "EXTERNAL_ACCOUNT_UPDATED",
                "email=" + saved.getEmailAddress() + ", secretChanged=" + secretChanged + ", sourceIdentityChanged=" + identityChanged);
        return toView(saved);
    }

    @Transactional
    public void delete(UUID actorId, Set<String> authorities, UUID accountId) {
        ExternalMailAccount account = accessibleAccount(actorId, authorities, accountId);
        UUID ownerId = account.getOwnerId();
        String email = account.getEmailAddress();
        accounts.delete(account);
        audit(actorId, null, ownerId, "EXTERNAL_ACCOUNT_DELETED", "email=" + email);
    }

    @Transactional
    public ExternalMailDtos.AccountView setEnabled(UUID actorId, Set<String> authorities, UUID accountId,
                                                    ExternalMailDtos.EnabledRequest req) {
        ExternalMailAccount account = accessibleAccount(actorId, authorities, accountId);
        if (req != null && req.enabled() != null) account.setEnabled(req.enabled());
        if (req != null && req.syncEnabled() != null) account.setSyncEnabled(req.syncEnabled());
        if (req != null && req.sendEnabled() != null) account.setSendEnabled(req.sendEnabled() && account.isSmtpEnabled());
        if (!account.isEnabled()) {
            account.setSyncLockOwner(null);
            account.setSyncLockUntil(null);
            account.setNextSyncAt(null);
        } else if (account.isSyncEnabled()) {
            account.setNextSyncAt(Instant.now());
        }
        ExternalMailAccount saved = accounts.saveAndFlush(account);
        audit(actorId, saved, "EXTERNAL_ACCOUNT_TOGGLED",
                "enabled=" + saved.isEnabled() + ", syncEnabled=" + saved.isSyncEnabled() + ", sendEnabled=" + saved.isSendEnabled());
        return toView(saved);
    }

    @Transactional
    public void resetSyncState(UUID actorId, Set<String> authorities, UUID accountId) {
        ExternalMailAccount account = accessibleAccount(actorId, authorities, accountId);
        folderStates.deleteAllByAccount_Id(account.getId());
        account.setLastError(null);
        account.setConsecutiveFailures(0);
        account.setSyncLockOwner(null);
        account.setSyncLockUntil(null);
        account.setNextSyncAt(account.isEnabled() && account.isSyncEnabled() ? Instant.now() : null);
        accounts.saveAndFlush(account);
        audit(actorId, account, "EXTERNAL_SYNC_STATE_RESET", "folder cursors reset; imported-message deduplication retained");
    }

    @Transactional
    public ExternalMailDtos.TestConnectionResponse test(UUID actorId, Set<String> authorities,
                                                        ExternalMailDtos.AccountRequest req) {
        requireReady();
        if (req == null) throw bad("Заполните параметры внешнего ящика");
        ExternalMailAccount existing = null;
        if (req.existingAccountId() != null) {
            existing = accessibleAccount(actorId, authorities, req.existingAccountId());
        }
        UUID ownerId = existing != null ? existing.getOwnerId()
                : (req.targetUserId() == null ? actorId : req.targetUserId());
        requireManage(actorId, ownerId, authorities);
        String email = req.emailAddress() == null || req.emailAddress().isBlank()
                ? (existing == null ? null : existing.getEmailAddress()) : normalizeEmail(req.emailAddress());
        if (email == null) throw bad("Укажите адрес внешнего ящика");
        String secret;
        if (req.secret() != null && !req.secret().isBlank()) {
            secret = requireSecret(req.secret(), true);
        } else if (existing != null) {
            secret = cipher.decrypt(existing.getSecretEnc());
        } else {
            throw bad("Укажите пароль приложения внешнего ящика");
        }
        ExternalMailAccount draft = new ExternalMailAccount();
        draft.setId(existing == null ? null : existing.getId());
        draft.setOwnerId(ownerId);
        if (existing != null) {
            draft.setSecretEnc(existing.getSecretEnc());
        }
        applyRequest(draft, req, existing, email, ownerId);
        ExternalMailDtos.TestConnectionResponse result = doTest(draft, secret);
        if (existing != null) {
            audit(actorId, existing, "EXTERNAL_CONNECTION_TESTED",
                    "incomingOk=" + result.incomingOk() + ", smtpOk=" + result.smtpOk());
        }
        return result;
    }

    @Transactional
    public ExternalMailDtos.TestConnectionResponse testStored(UUID actorId, Set<String> authorities, UUID accountId) {
        ExternalMailAccount account = accessibleAccount(actorId, authorities, accountId);
        ExternalMailDtos.TestConnectionResponse result = doTest(account, cipher.decrypt(account.getSecretEnc()));
        audit(actorId, account, "EXTERNAL_CONNECTION_TESTED",
                "incomingOk=" + result.incomingOk() + ", smtpOk=" + result.smtpOk());
        return result;
    }

    @Transactional(readOnly = true)
    public List<ExternalMailDtos.AuditView> audit(UUID actorId, Set<String> authorities, UUID accountId) {
        accessibleAccount(actorId, authorities, accountId);
        return auditEvents.findTop50ByAccount_IdOrderByCreatedAtDesc(accountId).stream()
                .map(e -> new ExternalMailDtos.AuditView(e.getId(),
                        e.getAccount() == null ? accountId : e.getAccount().getId(),
                        e.getActorId(), e.getOwnerId(), e.getAction(), e.getDetails(), e.getCreatedAt()))
                .toList();
    }

    /** Только для internal-контроллера: mail-smtp-service получает SMTP/IMAP конфигурацию just-in-time. */
    @Transactional(readOnly = true)
    public ExternalMailDtos.TransportConfigView transportConfig(UUID accountId) {
        requireReady();
        ExternalMailAccount account = accounts.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Внешний ящик не найден"));
        if (!account.isEnabled() || !account.isSendEnabled() || !account.isSmtpEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Отправка через этот внешний ящик отключена");
        }
        return new ExternalMailDtos.TransportConfigView(
                account.getId(), account.getOwnerId(), account.getEmailAddress(), account.getDisplayName(),
                account.getProvider(), account.getIncomingProtocol(), account.getIncomingHost(), account.getIncomingPort(),
                account.getIncomingSecurity(), account.getIncomingUsername(), cipher.decrypt(account.getSecretEnc()),
                account.getSmtpHost(), account.getSmtpPort(), account.getSmtpSecurity(), account.getSmtpUsername(),
                account.isSaveSentOnServer(), account.getSentFolderName());
    }

    public ExternalMailClient.AccountConfig toConfig(ExternalMailAccount account) {
        return toConfig(account, cipher.decrypt(account.getSecretEnc()));
    }

    public ExternalMailClient.AccountConfig toConfig(ExternalMailAccount account, String secret) {
        return new ExternalMailClient.AccountConfig(
                account.getId(), account.getOwnerId(), account.getEmailAddress(), account.getDisplayName(),
                account.getProvider(), account.getIncomingProtocol(), account.getIncomingHost(), account.getIncomingPort(),
                account.getIncomingSecurity(), account.getIncomingUsername(), secret,
                account.isSmtpEnabled(), account.getSmtpHost(), account.getSmtpPort(), account.getSmtpSecurity(),
                account.getSmtpUsername(), account.isMarkSeenOnServer(), account.isDeleteAfterFetch());
    }

    public ExternalMailAccount accessibleAccount(UUID actorId, Set<String> authorities, UUID accountId) {
        ExternalMailAccount account = accounts.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Внешний ящик не найден"));
        boolean admin = has(authorities, ADMIN_PERMISSION);
        if (!actorId.equals(account.getOwnerId()) && !admin) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Внешний ящик не найден");
        }
        requireManage(actorId, account.getOwnerId(), authorities);
        return account;
    }

    public void audit(UUID actorId, ExternalMailAccount account, String action, String details) {
        audit(actorId, account, account == null ? null : account.getOwnerId(), action, details);
    }

    public void audit(UUID actorId, ExternalMailAccount account, UUID ownerId, String action, String details) {
        ExternalMailAuditEvent event = new ExternalMailAuditEvent();
        event.setAccount(account);
        event.setActorId(actorId);
        event.setOwnerId(ownerId);
        event.setAction(action);
        event.setDetails(truncate(details, 2000));
        auditEvents.save(event);
    }

    public ExternalMailDtos.AccountView toView(ExternalMailAccount a) {
        long queued = a.getId() == null ? 0 : imported.countByAccount_IdAndStatus(a.getId(), ExternalMailImportedMessage.Status.QUEUED);
        long delivered = a.getId() == null ? 0 : imported.countByAccount_IdAndStatus(a.getId(), ExternalMailImportedMessage.Status.DELIVERED);
        long failed = a.getId() == null ? 0 : imported.countByAccount_IdAndStatus(a.getId(), ExternalMailImportedMessage.Status.FAILED);
        return new ExternalMailDtos.AccountView(
                a.getId(), a.getOwnerId(), a.getCreatedBy(), a.getOwnerKind(), a.getLabel(), a.getEmailAddress(),
                a.getDisplayName(), a.getProvider(), a.getAuthType(), a.getIncomingProtocol(), a.getIncomingHost(),
                a.getIncomingPort(), a.getIncomingSecurity(), a.getIncomingUsername(),
                a.getSecretEnc() != null && !a.getSecretEnc().isBlank(), a.isSmtpEnabled(), a.getSmtpHost(), a.getSmtpPort(),
                a.getSmtpSecurity(), a.getSmtpUsername(), a.getTargetFolder() == null ? null : a.getTargetFolder().getId(),
                a.isEnabled(), a.isSyncEnabled(), a.isSendEnabled(), a.getInitialSyncMode(), a.getSyncScope(),
                a.getPollIntervalSeconds(), a.isLeaveOnServer(), a.isMarkSeenOnServer(), a.isDeleteAfterFetch(),
                a.isSaveSentOnServer(), a.getSentFolderName(), a.getLastSyncAt(), a.getLastSuccessAt(), a.getNextSyncAt(),
                a.getLastError(), a.getConsecutiveFailures(),
                a.getSyncLockUntil() != null && a.getSyncLockUntil().isAfter(Instant.now()),
                queued, delivered, failed, a.getCreatedAt(), a.getUpdatedAt());
    }

    // ------------------------------------------------------------------ internals

    private ExternalMailDtos.PresetView preset(ExternalMailAccount.Provider provider) {
        ExternalMailProviderPreset p = ExternalMailProviderPreset.forProvider(provider);
        return new ExternalMailDtos.PresetView(provider,
                p.incomingHost(ExternalMailAccount.Protocol.IMAP), p.incomingPort(ExternalMailAccount.Protocol.IMAP),
                p.incomingHost(ExternalMailAccount.Protocol.POP3), p.incomingPort(ExternalMailAccount.Protocol.POP3),
                p.smtpHost(), p.smtpPort(), p.incomingSecurity(), p.smtpSecurity(), p.hint());
    }

    private void applyRequest(ExternalMailAccount account, ExternalMailDtos.AccountRequest req,
                              ExternalMailAccount existing, String email, UUID ownerId) {
        ExternalMailAccount.Provider provider = pick(req.provider(), existing == null ? null : existing.getProvider(),
                ExternalMailAccount.Provider.CUSTOM);
        ExternalMailAccount.Protocol protocol = pick(req.incomingProtocol(), existing == null ? null : existing.getIncomingProtocol(),
                ExternalMailAccount.Protocol.IMAP);
        ExternalMailAccount.AuthType authType = pick(req.authType(), existing == null ? null : existing.getAuthType(),
                ExternalMailAccount.AuthType.APP_PASSWORD);
        if (authType == ExternalMailAccount.AuthType.OAUTH2) {
            throw bad("OAuth2 для внешних провайдеров ещё не подключён: используйте пароль приложения провайдера");
        }
        account.setOwnerKind(pick(req.ownerKind(), existing == null ? null : existing.getOwnerKind(),
                ExternalMailAccount.OwnerKind.PERSONAL));
        account.setEmailAddress(email);
        account.setLabel(cleanLine(pickText(req.label(), existing == null ? null : existing.getLabel(), email), 128));
        account.setDisplayName(cleanNullable(req.displayName() == null && existing != null ? existing.getDisplayName() : req.displayName(), 256));
        account.setProvider(provider);
        account.setAuthType(authType);
        account.setIncomingProtocol(protocol);

        String incomingUsername = cleanLine(pickText(req.incomingUsername(),
                existing == null ? null : existing.getIncomingUsername(), email), 320);
        if (incomingUsername.contains(" ")) throw bad("Имя пользователя внешнего ящика не должно содержать пробелы");
        account.setIncomingUsername(incomingUsername);

        boolean smtpEnabled = pick(req.smtpEnabled(), existing == null ? null : existing.isSmtpEnabled(), true);
        ExternalMailProviderPreset preset = ExternalMailProviderPreset.forProvider(provider);
        if (preset != null) {
            account.setIncomingHost(preset.incomingHost(protocol));
            account.setIncomingPort(preset.incomingPort(protocol));
            account.setIncomingSecurity(preset.incomingSecurity());
            account.setSmtpHost(preset.smtpHost());
            account.setSmtpPort(preset.smtpPort());
            account.setSmtpSecurity(preset.smtpSecurity());
        } else {
            String host = pickText(req.incomingHost(), existing == null ? null : existing.getIncomingHost(), null);
            if (host == null) throw bad("Укажите IMAP/POP3 сервер внешнего ящика");
            Integer port = pick(req.incomingPort(), existing == null ? null : existing.getIncomingPort(), null);
            if (port == null) throw bad("Укажите порт IMAP/POP3 сервера внешнего ящика");
            account.setIncomingHost(host.trim());
            account.setIncomingPort(port);
            account.setIncomingSecurity(pick(req.incomingSecurity(), existing == null ? null : existing.getIncomingSecurity(),
                    ExternalMailAccount.Security.SSL_TLS));
            account.setSmtpHost(cleanNullable(req.smtpHost() == null && existing != null ? existing.getSmtpHost() : req.smtpHost(), 255));
            account.setSmtpPort(pick(req.smtpPort(), existing == null ? null : existing.getSmtpPort(), null));
            account.setSmtpSecurity(pick(req.smtpSecurity(), existing == null ? null : existing.getSmtpSecurity(),
                    ExternalMailAccount.Security.SSL_TLS));
        }
        account.setSmtpUsername(cleanLine(pickText(req.smtpUsername(),
                existing == null ? null : existing.getSmtpUsername(), incomingUsername), 320));
        account.setSmtpEnabled(smtpEnabled);

        ExternalMailEndpointPolicy.Endpoint incoming = endpointPolicy.validate(
                account.getIncomingHost(), account.getIncomingPort(), account.getIncomingSecurity(), preset != null);
        account.setIncomingHost(incoming.host());
        boolean sendEnabled = pick(req.sendEnabled(), existing == null ? null : existing.isSendEnabled(), smtpEnabled) && smtpEnabled;
        if (smtpEnabled) {
            if (account.getSmtpHost() == null || account.getSmtpPort() == null) {
                throw bad("Укажите SMTP сервер и порт провайдера либо отключите отправку через внешний ящик");
            }
            ExternalMailEndpointPolicy.Endpoint smtp = endpointPolicy.validate(
                    account.getSmtpHost(), account.getSmtpPort(), account.getSmtpSecurity(), preset != null);
            account.setSmtpHost(smtp.host());
        }
        account.setSendEnabled(sendEnabled);
        account.setEnabled(pick(req.enabled(), existing == null ? null : existing.isEnabled(), true));
        account.setSyncEnabled(pick(req.syncEnabled(), existing == null ? null : existing.isSyncEnabled(), true));
        account.setInitialSyncMode(pick(req.initialSyncMode(), existing == null ? null : existing.getInitialSyncMode(),
                ExternalMailAccount.InitialSyncMode.NEW_ONLY));
        account.setSyncScope(pick(req.syncScope(), existing == null ? null : existing.getSyncScope(),
                ExternalMailAccount.SyncScope.INBOX_ONLY));
        int poll = pick(req.pollIntervalSeconds(), existing == null ? null : existing.getPollIntervalSeconds(), 300);
        if (poll < 60 || poll > 86400) throw bad("Интервал проверки должен быть от 60 до 86400 секунд");
        account.setPollIntervalSeconds(poll);
        boolean deleteAfterFetch = pick(req.deleteAfterFetch(), existing == null ? null : existing.isDeleteAfterFetch(), false);
        boolean leaveOnServer = pick(req.leaveOnServer(), existing == null ? null : existing.isLeaveOnServer(), true);
        if (deleteAfterFetch && leaveOnServer) {
            throw bad("Нельзя одновременно оставлять письма на сервере и удалять их после сбора");
        }
        account.setLeaveOnServer(leaveOnServer);
        account.setDeleteAfterFetch(deleteAfterFetch);
        account.setMarkSeenOnServer(pick(req.markSeenOnServer(), existing == null ? null : existing.isMarkSeenOnServer(), false));
        account.setSaveSentOnServer(pick(req.saveSentOnServer(), existing == null ? null : existing.isSaveSentOnServer(), true));
        account.setSentFolderName(cleanNullable(req.sentFolderName() == null && existing != null
                ? existing.getSentFolderName() : req.sentFolderName(), 512));

        if (req.targetFolderId() == null) {
            account.setTargetFolder(null);
        } else {
            MailFolder target = folders.findByIdAndOwnerId(req.targetFolderId(), ownerId)
                    .orElseThrow(() -> bad("Целевая папка Corvex не найдена у владельца ящика"));
            account.setTargetFolder(target);
        }
    }

    private ExternalMailDtos.TestConnectionResponse doTest(ExternalMailAccount account, String secret) {
        ExternalMailClient.AccountConfig config = toConfig(account, secret);
        boolean incomingOk = false;
        String incomingMessage;
        int folderCount = 0;
        int inboxCount = 0;
        try {
            ExternalMailClient.TestResult result = client.testIncoming(config);
            incomingOk = result.ok();
            incomingMessage = result.message();
            folderCount = result.folderCount();
            inboxCount = result.inboxMessageCount();
        } catch (ExternalMailClient.ExternalMailClientException e) {
            incomingMessage = e.category() + ": " + e.getMessage();
        }
        boolean smtpTested = account.isSmtpEnabled() && account.getSmtpHost() != null && account.getSmtpPort() != null;
        boolean smtpOk = false;
        String smtpMessage = smtpTested ? null : "SMTP провайдера не настроен";
        if (smtpTested) {
            try {
                ExternalMailClient.TestResult result = client.testSmtp(config);
                smtpOk = result.ok();
                smtpMessage = result.message();
            } catch (ExternalMailClient.ExternalMailClientException e) {
                smtpMessage = e.category() + ": " + e.getMessage();
            }
        }
        return new ExternalMailDtos.TestConnectionResponse(incomingOk, incomingMessage, folderCount, inboxCount,
                smtpTested, smtpOk, smtpMessage);
    }

    private void testOrThrow(ExternalMailAccount account, String secret) {
        ExternalMailDtos.TestConnectionResponse result = doTest(account, secret);
        if (!result.incomingOk() || (result.smtpTested() && account.isSendEnabled() && !result.smtpOk())) {
            throw bad("Проверка подключения не пройдена. Входящие: " + result.incomingMessage()
                    + "; SMTP: " + result.smtpMessage());
        }
    }

    private void requireReady() {
        if (!props.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "External Mail Connector отключён политикой сервера");
        }
        cipher.requireConfigured();
    }

    private AuthUserClient.MailboxOwner requireActiveOwner(UUID ownerId) {
        AuthUserClient.MailboxOwner owner = auth.findById(ownerId)
                .orElseThrow(() -> bad("Пользователь-владелец Corvex не найден"));
        if (!owner.active()) throw bad("Пользователь-владелец Corvex неактивен");
        return owner;
    }

    private void requireManage(UUID actorId, UUID ownerId, Set<String> authorities) {
        if (has(authorities, ADMIN_PERMISSION)) return;
        if (actorId.equals(ownerId) && has(authorities, PERMISSION)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Нет права подключать внешние ящики (" + PERMISSION + ")");
    }

    private void requireAdmin(Set<String> authorities) {
        if (has(authorities, ADMIN_PERMISSION)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Нет права администрировать внешние ящики сотрудников (" + ADMIN_PERMISSION + ")");
    }

    private ExternalMailDtos.OwnerView toOwnerView(AuthUserClient.UserSummary owner) {
        return new ExternalMailDtos.OwnerView(
                owner.id(), owner.username(), owner.email(), owner.displayName(), owner.department(),
                owner.status(), accounts.countByOwnerId(owner.id()));
    }

    private static String folderPath(MailFolder folder, Map<UUID, MailFolder> byId) {
        List<String> parts = new java.util.ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        MailFolder current = folder;
        while (current != null && current.getId() != null && visited.add(current.getId())) {
            parts.add(0, current.getName());
            UUID parentId = current.getParent() == null ? null : current.getParent().getId();
            current = parentId == null ? null : byId.get(parentId);
        }
        return String.join(" / ", parts);
    }

    private static boolean has(Set<String> authorities, String permission) {
        return authorities != null && (authorities.contains(permission) || authorities.contains("*"));
    }

    private static String sourceIdentity(ExternalMailAccount a) {
        return String.join("|", Objects.toString(a.getProvider()), Objects.toString(a.getIncomingProtocol()),
                Objects.toString(a.getIncomingHost()), String.valueOf(a.getIncomingPort()),
                Objects.toString(a.getIncomingSecurity()), Objects.toString(a.getIncomingUsername()),
                Objects.toString(a.getEmailAddress()));
    }

    private static String normalizeEmail(String raw) {
        if (raw == null || raw.isBlank()) throw bad("Укажите адрес внешнего ящика");
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (email.contains("<") || email.contains(">") || email.contains(" ") || email.length() > 320) {
            throw bad("Некорректный адрес внешнего ящика");
        }
        try {
            InternetAddress parsed = new InternetAddress(email, true);
            parsed.validate();
            if (!email.equalsIgnoreCase(parsed.getAddress())) throw bad("Некорректный адрес внешнего ящика");
        } catch (AddressException e) {
            throw bad("Некорректный адрес внешнего ящика");
        }
        return email;
    }

    private static String requireSecret(String raw, boolean required) {
        if ((raw == null || raw.isBlank())) {
            if (required) throw bad("Укажите пароль приложения внешнего ящика");
            return null;
        }
        String secret = raw.trim();
        if (secret.length() > 1024 || secret.contains("\r") || secret.contains("\n")) {
            throw bad("Некорректный пароль приложения внешнего ящика");
        }
        return secret;
    }

    private static <T> T pick(T requested, T existing, T fallback) {
        return requested != null ? requested : (existing != null ? existing : fallback);
    }

    private static String pickText(String requested, String existing, String fallback) {
        if (requested != null && !requested.isBlank()) return requested.trim();
        if (existing != null && !existing.isBlank()) return existing.trim();
        return fallback;
    }

    private static String cleanLine(String value, int max) {
        if (value == null || value.isBlank()) throw bad("Обязательное поле внешнего ящика не заполнено");
        String clean = value.replace("\r", "").replace("\n", "").trim();
        if (clean.length() > max) throw bad("Значение поля внешнего ящика слишком длинное");
        return clean;
    }

    private static String cleanNullable(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String clean = value.replace("\r", "").replace("\n", "").trim();
        if (clean.length() > max) throw bad("Значение поля внешнего ящика слишком длинное");
        return clean;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
