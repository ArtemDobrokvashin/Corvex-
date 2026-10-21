package ru.corvex.mailstorage.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.ExternalMailDtos;
import ru.corvex.mailstorage.domain.ExternalMailSyncRun;
import ru.corvex.mailstorage.external.ExternalMailAccountService;
import ru.corvex.mailstorage.external.ExternalMailSyncService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Пользовательский API внешних ящиков. Пароль приложения принимается только в запросе и не возвращается. */
@RestController
@RequestMapping("/api/v1/mail/external-accounts")
public class ExternalMailController {

    private static final String CAN_MANAGE = "hasAnyAuthority('MAIL_EXTERNAL_ACCOUNTS','ADMIN_MAIL_POLICY')";

    private final ExternalMailAccountService accounts;
    private final ExternalMailSyncService sync;

    public ExternalMailController(ExternalMailAccountService accounts, ExternalMailSyncService sync) {
        this.accounts = accounts;
        this.sync = sync;
    }

    @GetMapping("/presets")
    @PreAuthorize("hasAnyAuthority('MAIL_EXTERNAL_ACCOUNTS','ADMIN_MAIL_POLICY','MAIL_READ_OWN')")
    List<ExternalMailDtos.PresetView> presets() {
        return accounts.presets();
    }

    @GetMapping("/owners")
    @PreAuthorize("hasAuthority('ADMIN_MAIL_POLICY')")
    List<ExternalMailDtos.OwnerView> owners(@AuthenticationPrincipal Jwt jwt,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) Integer limit) {
        return accounts.searchOwners(userId(jwt), permissions(jwt), q, limit);
    }

    @GetMapping("/owners/{ownerId}")
    @PreAuthorize(CAN_MANAGE)
    ExternalMailDtos.OwnerView owner(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID ownerId) {
        return accounts.owner(userId(jwt), permissions(jwt), ownerId);
    }

    @GetMapping("/owners/{ownerId}/folders")
    @PreAuthorize(CAN_MANAGE)
    List<ExternalMailDtos.TargetFolderView> ownerFolders(@AuthenticationPrincipal Jwt jwt,
                                                         @PathVariable UUID ownerId) {
        return accounts.ownerFolders(userId(jwt), permissions(jwt), ownerId);
    }

    @GetMapping
    @PreAuthorize(CAN_MANAGE)
    List<ExternalMailDtos.AccountView> list(@AuthenticationPrincipal Jwt jwt,
                                            @RequestParam(required = false) UUID ownerId) {
        return accounts.list(userId(jwt), permissions(jwt), ownerId);
    }

    @PostMapping
    @PreAuthorize(CAN_MANAGE)
    ResponseEntity<ExternalMailDtos.AccountView> create(@AuthenticationPrincipal Jwt jwt,
                                                         @RequestBody ExternalMailDtos.AccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accounts.create(userId(jwt), permissions(jwt), req));
    }

    @PostMapping("/test")
    @PreAuthorize(CAN_MANAGE)
    ExternalMailDtos.TestConnectionResponse test(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestBody ExternalMailDtos.AccountRequest req) {
        return accounts.test(userId(jwt), permissions(jwt), req);
    }

    @GetMapping("/{id}")
    @PreAuthorize(CAN_MANAGE)
    ExternalMailDtos.AccountView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return accounts.get(userId(jwt), permissions(jwt), id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(CAN_MANAGE)
    ExternalMailDtos.AccountView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                         @RequestBody ExternalMailDtos.AccountRequest req) {
        return accounts.update(userId(jwt), permissions(jwt), id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_MANAGE)
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        accounts.delete(userId(jwt), permissions(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/enabled")
    @PreAuthorize(CAN_MANAGE)
    ExternalMailDtos.AccountView enabled(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @RequestBody ExternalMailDtos.EnabledRequest req) {
        return accounts.setEnabled(userId(jwt), permissions(jwt), id, req);
    }

    @PostMapping("/{id}/test")
    @PreAuthorize(CAN_MANAGE)
    ExternalMailDtos.TestConnectionResponse testStored(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return accounts.testStored(userId(jwt), permissions(jwt), id);
    }

    @PostMapping("/{id}/sync")
    @PreAuthorize(CAN_MANAGE)
    ResponseEntity<ExternalMailDtos.SyncRunView> syncNow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(sync.requestSync(userId(jwt), permissions(jwt), id, ExternalMailSyncRun.Trigger.MANUAL));
    }

    @PostMapping("/{id}/migrate")
    @PreAuthorize(CAN_MANAGE)
    ResponseEntity<ExternalMailDtos.SyncRunView> migrate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(sync.requestSync(userId(jwt), permissions(jwt), id, ExternalMailSyncRun.Trigger.MIGRATION));
    }

    @PostMapping("/{id}/reset-sync")
    @PreAuthorize(CAN_MANAGE)
    ResponseEntity<Void> resetSync(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        accounts.resetSyncState(userId(jwt), permissions(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/runs")
    @PreAuthorize(CAN_MANAGE)
    List<ExternalMailDtos.SyncRunView> runs(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return sync.listRuns(userId(jwt), permissions(jwt), id);
    }

    @GetMapping("/{id}/audit")
    @PreAuthorize(CAN_MANAGE)
    List<ExternalMailDtos.AuditView> audit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return accounts.audit(userId(jwt), permissions(jwt), id);
    }

    private static Set<String> permissions(Jwt jwt) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        return permissions == null ? Set.of() : new HashSet<>(permissions);
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT subject не является UUID пользователя");
        }
    }
}
