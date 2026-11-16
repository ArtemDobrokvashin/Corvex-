package ru.corvex.mailstorage.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.MigrationDtos;
import ru.corvex.mailstorage.migration.MailMigrationService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Перенос почты из внешнего ящика. Задание создаётся на владельца внешнего ящика;
 * администратор видит его только при наличии ADMIN_MAIL_POLICY, как и сами внешние ящики.
 */
@RestController
@RequestMapping("/api/v1/mail/migration")
public class MailMigrationController {

    private static final String CAN_READ = "hasAnyAuthority('MAIL_EXTERNAL_ACCOUNTS','ADMIN_MAIL_POLICY')";

    private final MailMigrationService migrations;

    public MailMigrationController(MailMigrationService migrations) {
        this.migrations = migrations;
    }

    @PostMapping("/preview")
    @PreAuthorize(CAN_READ)
    MigrationDtos.PreviewView preview(@AuthenticationPrincipal Jwt jwt,
                                      @RequestBody MigrationDtos.ScopeRequest request) {
        return migrations.preview(userId(jwt), permissions(jwt), request);
    }

    @PostMapping
    @PreAuthorize(CAN_READ)
    ResponseEntity<MigrationDtos.JobView> create(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestBody MigrationDtos.ScopeRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(migrations.create(userId(jwt), permissions(jwt), request));
    }

    @GetMapping
    @PreAuthorize(CAN_READ)
    List<MigrationDtos.JobView> list(@AuthenticationPrincipal Jwt jwt) {
        return migrations.list(userId(jwt), permissions(jwt));
    }

    @GetMapping("/{jobId}")
    @PreAuthorize(CAN_READ)
    MigrationDtos.JobView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
        return migrations.get(userId(jwt), permissions(jwt), jobId);
    }

    @GetMapping("/{jobId}/report")
    @PreAuthorize(CAN_READ)
    MigrationDtos.ReportView report(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
        return migrations.report(userId(jwt), permissions(jwt), jobId);
    }

    @PostMapping("/{jobId}/pause")
    @PreAuthorize(CAN_READ)
    MigrationDtos.JobView pause(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
        return migrations.pause(userId(jwt), permissions(jwt), jobId);
    }

    @PostMapping("/{jobId}/resume")
    @PreAuthorize(CAN_READ)
    MigrationDtos.JobView resume(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
        return migrations.resume(userId(jwt), permissions(jwt), jobId);
    }

    @PostMapping("/{jobId}/cancel")
    @PreAuthorize(CAN_READ)
    MigrationDtos.JobView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
        return migrations.cancel(userId(jwt), permissions(jwt), jobId);
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "JWT subject не является UUID пользователя");
        }
    }

    private static Set<String> permissions(Jwt jwt) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        return permissions == null ? Set.of() : new HashSet<>(permissions);
    }
}
