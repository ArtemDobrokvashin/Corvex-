package ru.corvex.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.admin.api.dto.AlertDtos.AlertRuntimeStatus;
import ru.corvex.admin.api.dto.AlertDtos.AlertSettingsRequest;
import ru.corvex.admin.api.dto.AlertDtos.AlertSettingsView;
import ru.corvex.admin.service.AlertSettingsService;

import java.util.List;

/** Только члены SUPER_ADMIN: делегированного ADMIN_SERVER_CONFIG недостаточно. */
@RestController
@RequestMapping("/api/v1/admin/alerts")
public class AlertSettingsController {

    private final AlertSettingsService alerts;

    public AlertSettingsController(AlertSettingsService alerts) {
        this.alerts = alerts;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('ADMIN_SERVER_CONFIG')")
    AlertSettingsView settings(@AuthenticationPrincipal Jwt jwt) {
        requireSuperAdmin(jwt);
        return alerts.settings();
    }

    @GetMapping("/runtime")
    @PreAuthorize("hasAuthority('ADMIN_SERVER_CONFIG')")
    AlertRuntimeStatus runtime(@AuthenticationPrincipal Jwt jwt) {
        requireSuperAdmin(jwt);
        return alerts.runtimeStatus();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('ADMIN_SERVER_CONFIG')")
    AlertSettingsView replace(@RequestBody AlertSettingsRequest request,
                              @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        requireSuperAdmin(jwt);
        return alerts.replace(request, Actors.from(jwt, http));
    }

    private static void requireSuperAdmin(Jwt jwt) {
        List<String> groups = jwt == null ? null : jwt.getClaimAsStringList("groups");
        if (groups == null || !groups.contains("SUPER_ADMIN")) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Настройка алертов доступна только суперадминистратору");
        }
    }
}
