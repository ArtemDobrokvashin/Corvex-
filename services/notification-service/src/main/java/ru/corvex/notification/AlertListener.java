package ru.corvex.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alertmanager webhook → RabbitMQ → внутренняя почта. Категории, получатели и
 * repeat policy применяются здесь из admin-service; grouping/silences остаются в Alertmanager.
 */
@Component
public class AlertListener {

    private static final Logger log = LoggerFactory.getLogger(AlertListener.class);

    private final JavaMailSender mailSender;
    private final AlertRuntimePolicyProvider policies;
    private final Clock clock;
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();

    public AlertListener(JavaMailSender mailSender, AlertRuntimePolicyProvider policies) {
        this(mailSender, policies, Clock.systemUTC());
    }

    AlertListener(JavaMailSender mailSender, AlertRuntimePolicyProvider policies, Clock clock) {
        this.mailSender = mailSender;
        this.policies = policies;
        this.clock = clock;
    }

    public record AlertEvent(String alertName, String severity, String service,
                             String summary, String description, String fingerprint, String status) {
        public AlertEvent(String alertName, String severity, String service, String summary, String description) {
            this(alertName, severity, service, summary, description, "", "firing");
        }
    }

    @RabbitListener(queues = "corvex.alerts")
    public void onAlert(AlertEvent event) {
        AlertRuntimePolicyProvider.Policy policy = policies.current();
        if (!policy.internalEmailEnabled()
                || !policy.categories().getOrDefault(event.alertName(), true)) {
            log.info("Internal alert notification filtered: {} {}", event.severity(), event.alertName());
            return;
        }
        String eventKey = event.fingerprint() == null || event.fingerprint().isBlank()
                ? event.alertName() + "|" + event.service()
                : event.fingerprint();
        boolean resolved = "resolved".equalsIgnoreCase(event.status());
        Instant now = clock.instant();
        if (!resolved) {
            int repeatSeconds = "critical".equalsIgnoreCase(event.severity())
                    ? policy.repeatCriticalSeconds() : policy.repeatNormalSeconds();
            Instant previous = lastSent.get(eventKey);
            if (previous != null && now.isBefore(previous.plusSeconds(repeatSeconds))) {
                log.debug("Internal alert repeat suppressed: {} {}", event.severity(), event.alertName());
                return;
            }
        }

        String[] recipients = Arrays.stream(policy.internalRecipients().split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toArray(String[]::new);
        var msg = new SimpleMailMessage();
        msg.setFrom(policy.internalFrom());
        msg.setTo(recipients);
        msg.setSubject("[Corvex %s] %s (%s)".formatted(event.severity(), event.alertName(), event.service()));
        msg.setText(event.summary() + "\n\n" + event.description()
                + "\n\nДашборды: Grafana → Corvex Overview; логи: Grafana → Loki (сервис " + event.service() + ").");
        mailSender.send(msg);
        if (resolved) {
            lastSent.remove(eventKey);
        } else {
            lastSent.put(eventKey, now);
        }
        log.warn("Alert email sent: {} {} {} -> {}", event.severity(), event.alertName(),
                resolved ? "resolved" : "firing", String.join(",", recipients));
    }
}
