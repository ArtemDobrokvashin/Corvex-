package ru.corvex.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Email-алерты: Alertmanager шлёт webhook -&gt; событие в очередь {@code corvex.alerts} -&gt; письмо администраторам.
 * Важный нюанс эксплуатации: уведомление о падении почты не должно зависеть от самой почты Corvex,
 * поэтому получатели и SMTP-релей настраиваются ОТДЕЛЬНО (внешний/резервный SMTP, CORVEX_ALERT_*),
 * а локальный mail-smtp-service используется только как fallback первого выбора.
 * Дедупликация (один алерт — одно письмо в N минут) и эскалация выполняются Alertmanager'ом,
 * здесь только доставка + журнал отправленного.
 */
@Component
public class AlertListener {

    private static final Logger log = LoggerFactory.getLogger(AlertListener.class);

    private final JavaMailSender mailSender;
    private final String[] recipients;
    private final String from;

    public AlertListener(JavaMailSender mailSender,
                         @Value("${corvex.alert.recipients:admin@corp.local}") String[] recipients,
                         @Value("${corvex.alert.from:corvex-alert@corp.local}") String from) {
        this.mailSender = mailSender;
        this.recipients = recipients;
        this.from = from;
    }

    public record AlertEvent(String alertName, String severity, String service, String summary, String description) {
    }

    @RabbitListener(queues = "corvex.alerts")
    public void onAlert(AlertEvent event) {
        var msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(recipients);
        msg.setSubject("[Corvex %s] %s (%s)".formatted(event.severity(), event.alertName(), event.service()));
        msg.setText(event.summary() + "\n\n" + event.description()
                + "\n\nДашборды: Grafana → Corvex Overview; логи: Grafana → Loki (сервис " + event.service() + ").");
        mailSender.send(msg);
        log.warn("Alert email sent: {} {} -> {}", event.severity(), event.alertName(), String.join(",", recipients));
    }
}
