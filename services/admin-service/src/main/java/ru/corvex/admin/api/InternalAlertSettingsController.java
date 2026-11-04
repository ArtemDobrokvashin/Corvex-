package ru.corvex.admin.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.admin.api.dto.AlertDtos.NotificationPolicy;
import ru.corvex.admin.api.dto.AlertDtos.RelayConfig;
import ru.corvex.admin.service.AlertSettingsService;

/** Internal token проверяется InternalApiTokenFilter до попадания в этот controller. */
@RestController
@RequestMapping("/internal/alerts")
public class InternalAlertSettingsController {

    private final AlertSettingsService alerts;

    public InternalAlertSettingsController(AlertSettingsService alerts) {
        this.alerts = alerts;
    }

    @GetMapping("/policy")
    NotificationPolicy policy() {
        return alerts.notificationPolicy();
    }

    @GetMapping("/relay-config")
    RelayConfig relayConfig() {
        return alerts.relayConfig();
    }
}
