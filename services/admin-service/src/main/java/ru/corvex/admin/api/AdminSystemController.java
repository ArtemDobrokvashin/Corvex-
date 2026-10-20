package ru.corvex.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.admin.api.dto.AdminDtos.AnalyticsResponse;
import ru.corvex.admin.api.dto.AdminDtos.AuditView;
import ru.corvex.admin.api.dto.AdminDtos.BackupResponse;
import ru.corvex.admin.api.dto.AdminDtos.HealthResponse;
import ru.corvex.admin.api.dto.AdminDtos.PageResult;
import ru.corvex.admin.client.AuthServiceClient;
import ru.corvex.admin.service.AdminSystemService;
import ru.corvex.admin.service.AuditService;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Настройки, аудит, аналитика, здоровье и бэкап в админке. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminSystemController {

    private final AdminSystemService system;
    private final AuditService audit;
    private final AuthServiceClient authClient;

    public AdminSystemController(AdminSystemService system, AuditService audit, AuthServiceClient authClient) {
        this.system = system;
        this.audit = audit;
        this.authClient = authClient;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('ADMIN_SERVER_CONFIG')")
    Map<String, String> settings() {
        return system.settings();
    }

    @PatchMapping("/settings")
    @PreAuthorize("hasAuthority('ADMIN_SERVER_CONFIG')")
    Map<String, String> patchSettings(@RequestBody Map<String, String> patch,
                                      @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return system.patchSettings(patch, Actors.from(jwt, http));
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    PageResult<AuditView> audit(@RequestParam(required = false) UUID actorId,
                                @RequestParam(required = false) String action,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        return audit.query(actorId, action, from, to, page, size);
    }

    @GetMapping("/analytics/activity")
    @PreAuthorize("hasAnyAuthority('ANALYTICS_VIEW_COMPANY','ANALYTICS_VIEW_OWN_UNIT')")
    AnalyticsResponse analytics(@RequestParam(required = false) String period,
                                 @RequestParam(required = false) String department,
                                 @AuthenticationPrincipal Jwt jwt,
                                 Authentication authentication) {
        boolean companyScope = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ANALYTICS_VIEW_COMPANY"::equals);
        if (companyScope) {
            return system.analytics("COMPANY", department);
        }
        // Руководитель видит только своё подразделение: запрошенное извне игнорируется.
        UUID actorId = Actors.userId(jwt);
        String ownDepartment = actorId == null ? null : authClient.getUser(actorId).department();
        if (ownDepartment == null || ownDepartment.isBlank()) {
            return new AnalyticsResponse("OWN_UNIT", null, 0, 0, 0, 0, null, null, Instant.now());
        }
        return system.analytics("OWN_UNIT", ownDepartment);
    }

    @GetMapping("/system/health")
    @PreAuthorize("hasAnyAuthority('ADMIN_SERVER_CONFIG','ADMIN_USERS_MANAGE','ADMIN_GROUPS_MANAGE')")
    HealthResponse health() {
        return system.health();
    }

    @PostMapping("/backup/run")
    @PreAuthorize("hasAuthority('ADMIN_BACKUP')")
    ResponseEntity<BackupResponse> backup(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(system.requestBackup(Actors.from(jwt, http)));
    }
}
