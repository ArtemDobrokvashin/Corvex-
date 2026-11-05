package ru.corvex.notification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Несекретный revision для admin UI и эксплуатационной диагностики. */
@RestController
public class AlertRuntimeStatusController {

    private final AlertRuntimePolicyProvider policies;

    public AlertRuntimeStatusController(AlertRuntimePolicyProvider policies) {
        this.policies = policies;
    }

    @GetMapping("/internal/alerts/runtime-status")
    AlertRuntimePolicyProvider.Status status() {
        policies.current(); // status probe also refreshes a changed UI revision without waiting for an alert
        return policies.status();
    }
}
