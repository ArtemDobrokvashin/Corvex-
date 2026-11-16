package ru.corvex.mailstorage.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.AdminMigrationDtos;
import ru.corvex.mailstorage.migration.AdminMigrationService;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Админский экран «Перенос»: сводка по сотрудникам, массовое приглашение CSV без паролей
 * и поднятие квоты из отчёта. Право одно — ADMIN_MAIL_POLICY.
 */
@RestController
@RequestMapping("/api/v1/mail/admin/migrations")
public class AdminMigrationController {

    private static final String ADMIN = "hasAuthority('ADMIN_MAIL_POLICY')";
    private static final int MAX_CSV_BYTES = 1024 * 1024;

    private final AdminMigrationService migrations;

    public AdminMigrationController(AdminMigrationService migrations) {
        this.migrations = migrations;
    }

    @GetMapping
    @PreAuthorize(ADMIN)
    AdminMigrationDtos.Summary summary(@AuthenticationPrincipal Jwt jwt) {
        return migrations.summary(userId(jwt), permissions(jwt));
    }

    @PostMapping("/bulk")
    @PreAuthorize(ADMIN)
    AdminMigrationDtos.BulkResult bulk(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл пуст");
        }
        if (file.getSize() > MAX_CSV_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "CSV больше 1 МиБ");
        }
        return migrations.bulk(userId(jwt), permissions(jwt), file.getBytes());
    }

    @PostMapping("/{ownerId}/quota-bump")
    @PreAuthorize(ADMIN)
    AdminMigrationDtos.QuotaBumpResult quotaBump(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable UUID ownerId,
                                                 @RequestBody AdminMigrationDtos.QuotaBumpRequest request) {
        return migrations.quotaBump(userId(jwt), permissions(jwt), ownerId, request);
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
