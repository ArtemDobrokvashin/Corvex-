package ru.corvex.mailsmtp.outbound;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalSmtpClientTest {

    @Test
    void blocksLoopbackProviderEndpointBeforeNetworkCall() {
        ExternalSmtpClient client = new ExternalSmtpClient(false, false, "mail.company.local");
        var config = new ExternalAccountConfigClient.TransportConfig(
                UUID.randomUUID(), UUID.randomUUID(), "old@mail.ru", null, "CUSTOM",
                "IMAP", "127.0.0.1", 993, "SSL_TLS", "old@mail.ru", "secret",
                "127.0.0.1", 465, "SSL_TLS", "old@mail.ru", true, null);

        var results = client.send(config, "Subject: hi\r\n\r\nbody".getBytes(StandardCharsets.UTF_8),
                "old@mail.ru", List.of("client@example.org"));

        assertEquals(1, results.size());
        assertEquals(SmtpRelayClient.Status.PERMANENT, results.get(0).status());
    }
}
