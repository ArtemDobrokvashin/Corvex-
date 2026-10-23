package ru.corvex.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationConfigTest {

    private RabbitTemplate rabbit;
    private NotificationConfig.AlertWebhookController controller;

    @BeforeEach
    void setUp() {
        rabbit = mock(RabbitTemplate.class);
        controller = new NotificationConfig.AlertWebhookController(rabbit);
    }

    @Test
    void declaresDurableAlertsQueueAndJsonConverter() {
        NotificationConfig config = new NotificationConfig();

        Queue queue = config.alertsQueue();

        assertEquals(NotificationConfig.ALERTS_QUEUE, queue.getName());
        assertTrue(queue.isDurable());
        assertInstanceOf(Jackson2JsonMessageConverter.class, config.jsonMessageConverter());
    }

    @Test
    void webhookMapsAlertmanagerLabelsAndAnnotationsToQueueEvents() {
        Map<String, Object> alert = Map.of(
                "labels", Map.of(
                        "alertName", "DiskAlmostFull",
                        "severity", "warning",
                        "application", "mail-storage-service"),
                "annotations", Map.of(
                        "summary", "На диске осталось мало места",
                        "description", "Maildir volume is 91% full"),
                "status", "firing");

        controller.webhook(Map.of("alerts", List.of(alert)));

        AlertListener.AlertEvent event = singlePublishedEvent();
        assertEquals("DiskAlmostFull", event.alertName());
        assertEquals("warning", event.severity());
        assertEquals("mail-storage-service", event.service());
        assertEquals("На диске осталось мало места", event.summary());
        assertEquals("Maildir volume is 91% full", event.description());
    }

    @Test
    void webhookUsesInstanceAndStatusFallbacks() {
        Map<String, Object> alert = Map.of(
                "labels", Map.of("instance", "corvex-node-1"),
                "annotations", Map.of(),
                "status", "resolved");

        controller.webhook(Map.of("alerts", List.of(alert)));

        AlertListener.AlertEvent event = singlePublishedEvent();
        assertEquals("alert", event.alertName());
        assertEquals("warning", event.severity());
        assertEquals("corvex-node-1", event.service());
        assertEquals("", event.summary());
        assertEquals("resolved", event.description());
    }

    @Test
    void webhookUsesCorvexAndFiringDefaultsWhenAlertMapsAreMissingOrWrongTyped() {
        Map<String, Object> alert = Map.of(
                "labels", "not-a-map",
                "annotations", 42);

        controller.webhook(Map.of("alerts", List.of(alert)));

        AlertListener.AlertEvent event = singlePublishedEvent();
        assertEquals("alert", event.alertName());
        assertEquals("corvex", event.service());
        assertEquals("firing", event.description());
    }

    @Test
    void webhookPublishesEachAlertAndIgnoresNonMapItems() {
        controller.webhook(Map.of("alerts", List.of(
                "ignored",
                Map.of("labels", Map.of("alertName", "One")),
                Map.of("labels", Map.of("alertName", "Two")))));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbit, times(2)).convertAndSend(eq(NotificationConfig.ALERTS_QUEUE), captor.capture());
        assertEquals(List.of("One", "Two"), captor.getAllValues().stream()
                .map(value -> ((AlertListener.AlertEvent) value).alertName())
                .toList());
    }

    @Test
    void webhookIgnoresMissingOrNonListAlerts() {
        controller.webhook(Map.of());
        controller.webhook(Map.of("alerts", "not-a-list"));

        verifyNoInteractions(rabbit);
    }

    private AlertListener.AlertEvent singlePublishedEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(eq(NotificationConfig.ALERTS_QUEUE), captor.capture());
        return (AlertListener.AlertEvent) captor.getValue();
    }
}
