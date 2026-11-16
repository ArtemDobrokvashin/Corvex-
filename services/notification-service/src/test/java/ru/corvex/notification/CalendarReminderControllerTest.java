package ru.corvex.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CalendarReminderControllerTest {

    private JavaMailSender mailSender;
    private CalendarReminderController controller;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        controller = new CalendarReminderController(mailSender, new InternalTokenGuard("secret"), "corvex-calendar@company.local");
    }

    private static CalendarReminderController.ReminderRequest request(String to) {
        return new CalendarReminderController.ReminderRequest(to, "Планёрка",
                "2030-10-19T07:00:00Z", "Europe/Amsterdam", "Переговорная 2", 3, false);
    }

    @Test
    void sendsReminderInTheEventTimeZone() {
        var response = controller.reminder("secret", request("i.ivanov@company.local"));
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertEquals("i.ivanov@company.local", message.getValue().getTo()[0]);
        assertEquals("corvex-calendar@company.local", message.getValue().getFrom());
        assertTrue(message.getValue().getSubject().contains("Планёрка"));
        // 07:00Z — это 09:00 в Амстердаме; письмо не должно показывать UTC-время.
        assertTrue(message.getValue().getText().contains("19.10.2030 09:00"));
        assertTrue(message.getValue().getText().contains("Скоро событие"));
        assertTrue(message.getValue().getText().contains("Europe/Amsterdam"));
    }

    @Test
    void rejectsRequestWithoutValidInternalToken() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> controller.reminder("wrong", request("i.ivanov@company.local")));
        assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatusCode());
    }

    @Test
    void failsClosedWhenInternalTokenIsNotConfigured() {
        CalendarReminderController unconfigured = new CalendarReminderController(
                mailSender, new InternalTokenGuard(""), "corvex-calendar@company.local");
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> unconfigured.reminder("", request("i.ivanov@company.local")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
    }

    @Test
    void rejectsInvalidRecipientBeforeTouchingSmtp() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> controller.reminder("secret", request("not-an-address")));
        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
    }

    @Test
    void smtpFailureIsReportedAs503SoTheSchedulerRetries() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> controller.reminder("secret", request("i.ivanov@company.local")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
    }

    @Test
    void reportsStartedEventHonestlyAfterServerDowntime() {
        var response = controller.reminder("secret", new CalendarReminderController.ReminderRequest(
                "i.ivanov@company.local", "Планёрка",
                "2020-01-01T07:00:00Z", "Europe/Amsterdam", "", 0, false));
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertTrue(message.getValue().getText().contains("Событие уже началось"));
    }
}
