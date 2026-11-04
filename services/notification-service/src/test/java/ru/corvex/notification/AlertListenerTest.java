package ru.corvex.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AlertListenerTest {

    private JavaMailSender mailSender;
    private AlertRuntimePolicyProvider policies;
    private MutableClock clock;
    private AlertListener listener;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        policies = mock(AlertRuntimePolicyProvider.class);
        clock = new MutableClock(Instant.parse("2026-11-04T10:00:00Z"));
        when(policies.current()).thenAnswer(invocation -> policy(true));
        listener = new AlertListener(mailSender, policies, clock);
    }

    @Test
    void sendsAlertToConfiguredRecipientsWithServiceContext() {
        when(policies.current()).thenReturn(policy(true));

        listener.onAlert(event("fingerprint-1", "firing"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertEquals("alerts@corvex.local", message.getFrom());
        assertArrayEquals(new String[]{"admin@example.test", "duty@example.test"}, message.getTo());
        assertEquals("[Corvex critical] MailQueueBacklog (mail-smtp-service)", message.getSubject());
        assertTrue(message.getText().contains("Очередь исходящей почты растёт"));
        assertTrue(message.getText().contains("DLQ не пустая более 10 минут"));
        assertTrue(message.getText().contains("сервис mail-smtp-service"));
    }

    @Test
    void disabledCategoryAndDisabledInternalChannelNeverInvokeSmtp() {
        AlertRuntimePolicyProvider.Policy disabledCategory = policy(true);
        disabledCategory.categories().put("MailQueueBacklog", false);
        when(policies.current()).thenReturn(disabledCategory, policy(false));

        listener.onAlert(event("one", "firing"));
        listener.onAlert(event("two", "firing"));

        verifyNoInteractions(mailSender);
    }

    @Test
    void repeatUsesCriticalIntervalAndResolvedBypassesThrottle() {
        AlertListener.AlertEvent firing = event("same-fingerprint", "firing");
        listener.onAlert(firing);
        listener.onAlert(firing);
        verify(mailSender, times(1)).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));

        clock.advanceSeconds(1_801);
        listener.onAlert(firing);
        verify(mailSender, times(2)).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));

        listener.onAlert(event("same-fingerprint", "resolved"));
        verify(mailSender, times(3)).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
        listener.onAlert(firing);
        verify(mailSender, times(4)).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
    }

    private static AlertRuntimePolicyProvider.Policy policy(boolean internalEnabled) {
        return new AlertRuntimePolicyProvider.Policy(1, Instant.parse("2026-11-04T09:00:00Z"),
                14_400, 1_800, new java.util.LinkedHashMap<>(AlertRuntimePolicyProvider.defaultCategories()), internalEnabled,
                "admin@example.test,duty@example.test", "alerts@corvex.local");
    }

    private static AlertListener.AlertEvent event(String fingerprint, String status) {
        return new AlertListener.AlertEvent("MailQueueBacklog", "critical", "mail-smtp-service",
                "Очередь исходящей почты растёт", "DLQ не пустая более 10 минут", fingerprint, status);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
