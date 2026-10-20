package ru.corvex.mailsmtp.outbound;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmtpRelayClientTest {

    @Test
    void extractsPermanentSmtpCode() {
        assertEquals(550, SmtpRelayClient.smtpCode(new MessagingException("550 5.1.1 user unknown")));
    }

    @Test
    void extractsTemporarySmtpCodeFromCause() {
        MessagingException outer = new MessagingException("send failed", new Exception("451 temporary local problem"));
        assertEquals(451, SmtpRelayClient.smtpCode(outer));
    }
}
