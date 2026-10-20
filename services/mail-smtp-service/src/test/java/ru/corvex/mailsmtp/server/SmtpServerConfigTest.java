package ru.corvex.mailsmtp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmtpServerConfigTest {

    @Test
    void deniesRelayToForeignDomainsBeforeAuthLookup() {
        SmtpServerConfig config = new SmtpServerConfig();
        InboundMessageHandlerFactory.RecipientPolicy policy = config.recipientPolicy(
                "company.local", "http://localhost:1", "", new ObjectMapper());

        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_RELAY,
                policy.check("victim@partner.example"));
    }

    @Test
    void normalizesAngleBracketAddresses() {
        assertEquals("user@company.local", SmtpServerConfig.normalizeAddress(" <user@company.local> "));
    }
}
