package ru.corvex.notification;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Очередь алертов, JSON-конвертер и вебхук Alertmanager (infra/prometheus/alertmanager.yml
 * ссылается на /internal/alerts/webhook). Без этого класса: очередь corvex.alerts не
 * декларировалась, AlertEvent не десериализовался, вебхука не существовало.
 */
@Configuration
public class NotificationConfig {

    public static final String ALERTS_QUEUE = "corvex.alerts";

    @Bean
    Queue alertsQueue() {
        return QueueBuilder.durable(ALERTS_QUEUE).build();
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /** Принимает payload Alertmanager v4 и раскладывает алерты в очередь по одному. */
    @RestController
    static class AlertWebhookController {
        private final RabbitTemplate rabbit;

        AlertWebhookController(RabbitTemplate rabbit) {
            this.rabbit = rabbit;
        }

        @PostMapping("/internal/alerts/webhook")
        void webhook(@RequestBody Map<String, Object> payload) {
            Object alerts = payload.getOrDefault("alerts", List.of());
            if (alerts instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> alert) {
                        Map<?, ?> labels = (Map<?, ?>) alert.getOrDefault("labels", Map.of());
                        Map<?, ?> ann = (Map<?, ?>) alert.getOrDefault("annotations", Map.of());
                        var event = new AlertListener.AlertEvent(
                                String.valueOf(labels.getOrDefault("alertName", "alert")),
                                String.valueOf(labels.getOrDefault("severity", "warning")),
                                String.valueOf(labels.getOrDefault("application", labels.getOrDefault("instance", "corvex"))),
                                String.valueOf(ann.getOrDefault("summary", "")),
                                String.valueOf(ann.getOrDefault("description", String.valueOf(alert.getOrDefault("status", "firing")))));
                        rabbit.convertAndSend(ALERTS_QUEUE, event);
                    }
                }
            }
        }
    }
}
