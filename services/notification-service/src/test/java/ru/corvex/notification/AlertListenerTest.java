package ru.corvex.notification;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AlertListenerTest {

    @Test
    void sendsAlertToConfiguredRecipientsWithServiceContext() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        AlertListener listener = new AlertListener(
                mailSender,
                new String[]{"admin@example.test", "duty@example.test"},
                "alerts@corvex.local");

        listener.onAlert(new AlertListener.AlertEvent(
                "MailQueueGrowing",
                "critical",
                "mail-smtp-service",
                "Очередь исходящей почты растёт",
                "DLQ не пустая более 10 минут"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertEquals("alerts@corvex.local", message.getFrom());
        assertArrayEquals(new String[]{"admin@example.test", "duty@example.test"}, message.getTo());
        assertEquals("[Corvex critical] MailQueueGrowing (mail-smtp-service)", message.getSubject());
        assertTrue(message.getText().contains("Очередь исходящей почты растёт"));
        assertTrue(message.getText().contains("DLQ не пустая более 10 минут"));
        assertTrue(message.getText().contains("сервис mail-smtp-service"));
    }
}
