package ru.corvex.mailstorage.external;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.domain.ExternalMailAccount;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalSupportComponentsTest {

    @Test
    void propertiesExposeValuesAndClampOperationalLimits() {
        ExternalMailProperties low = new ExternalMailProperties(
                false, false, true, true, 3, 0, -5, 1, 2, 0);
        assertFalse(low.enabled());
        assertFalse(low.allowCustomHosts());
        assertTrue(low.allowPrivateNetworks());
        assertTrue(low.allowInsecurePlain());
        assertEquals(3, low.maxAccountsPerUser());
        assertEquals(1, low.safeBatchSize());
        assertEquals(1, low.safeMaxFolders());
        assertEquals(1, low.safeSchedulerThreads());
        assertEquals(1, low.connectTimeoutSeconds());
        assertEquals(2, low.readTimeoutSeconds());

        ExternalMailProperties high = new ExternalMailProperties(
                true, true, false, false, 10, 9999, 9999, 15, 60, 99);
        assertEquals(2000, high.safeBatchSize());
        assertEquals(1000, high.safeMaxFolders());
        assertEquals(16, high.safeSchedulerThreads());
        assertEquals(9999, high.batchSize());
        assertEquals(9999, high.maxFolders());
        assertEquals(99, high.schedulerThreads());
    }

    @Test
    void endpointPolicyAllowsExplicitPlainAndPrivateOptIns() {
        ExternalMailEndpointPolicy policy = policy(true, true, true);

        var privateEndpoint = policy.validate("192.168.10.25", 143,
                ExternalMailAccount.Security.PLAIN, false);
        assertEquals(List.of("192.168.10.25"), privateEndpoint.addresses());

        var reserved = policy.validate("198.51.100.25", 993,
                ExternalMailAccount.Security.SSL_TLS, false);
        assertEquals("198.51.100.25", reserved.host());
    }

    @Test
    void endpointPolicyAlwaysBlocksLoopbackLinkLocalMulticastAndAnyLocal() {
        ExternalMailEndpointPolicy permissive = policy(true, true, true);
        for (String host : List.of("127.0.0.1", "::1", "169.254.169.254", "224.0.0.1", "0.0.0.0", "localhost")) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> permissive.validate(host, 993, ExternalMailAccount.Security.SSL_TLS, false))
                    .getStatusCode(), host);
        }
    }

    @Test
    void endpointPolicyBlocksPrivateAndReservedRangesByDefault() {
        ExternalMailEndpointPolicy strict = policy(true, false, false);
        for (String host : List.of(
                "10.0.0.5", "172.16.0.5", "100.64.0.5", "192.0.2.5", "198.18.0.5",
                "198.51.100.5", "203.0.113.5", "240.0.0.5", "fc00::5", "2001:db8::5")) {
            assertThrows(ResponseStatusException.class,
                    () -> strict.validate(host, 993, ExternalMailAccount.Security.SSL_TLS, false), host);
        }
    }

    @Test
    void endpointPolicyValidatesCustomHostSwitchPortAndHostSyntax() {
        ExternalMailEndpointPolicy noCustom = policy(false, false, false);
        assertThrows(ResponseStatusException.class, () -> noCustom.validate(
                "8.8.8.8", 993, ExternalMailAccount.Security.SSL_TLS, false));
        assertEquals("8.8.8.8", noCustom.validate(
                "8.8.8.8", 993, ExternalMailAccount.Security.SSL_TLS, true).host());

        ExternalMailEndpointPolicy policy = policy(true, false, false);
        assertThrows(ResponseStatusException.class, () -> policy.validate(
                "8.8.8.8", 0, ExternalMailAccount.Security.SSL_TLS, false));
        assertThrows(ResponseStatusException.class, () -> policy.validate(
                "8.8.8.8", 65536, ExternalMailAccount.Security.SSL_TLS, false));
        for (String bad : new String[]{null, " ", "host/path", "user@host", "ho st", "bad_host", "a".repeat(256)}) {
            assertThrows(ResponseStatusException.class, () -> policy.normalizeHost(bad));
        }
        assertEquals("2001:4860:4860::8888", policy.normalizeHost("[2001:4860:4860::8888]"));
        assertEquals("xn--mnchen-3ya.example", policy.normalizeHost("MÜNCHEN.EXAMPLE"));
    }

    @Test
    void endpointPolicyReportsUnresolvableHost() {
        ExternalMailEndpointPolicy policy = policy(true, false, false);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> policy.validate("corvex-nonexistent.invalid", 993,
                        ExternalMailAccount.Security.SSL_TLS, false));
        assertTrue(error.getReason().contains("DNS"));
    }

    @Test
    void remainingProviderPresetsHaveExpectedEndpoints() {
        var gmail = ExternalMailProviderPreset.forProvider(ExternalMailAccount.Provider.GMAIL);
        assertEquals("imap.gmail.com", gmail.incomingHost(ExternalMailAccount.Protocol.IMAP));
        assertEquals("pop.gmail.com", gmail.incomingHost(ExternalMailAccount.Protocol.POP3));
        assertEquals(995, gmail.incomingPort(ExternalMailAccount.Protocol.POP3));
        assertEquals("imap.gmail.com", gmail.incomingHost(null));

        var outlook = ExternalMailProviderPreset.forProvider(ExternalMailAccount.Provider.OUTLOOK);
        assertEquals("outlook.office365.com", outlook.incomingHost(ExternalMailAccount.Protocol.IMAP));
        assertEquals("smtp-mail.outlook.com", outlook.smtpHost());
        assertEquals(ExternalMailAccount.Security.STARTTLS, outlook.smtpSecurity());
        assertNull(ExternalMailProviderPreset.forProvider(ExternalMailAccount.Provider.CUSTOM));
    }

    @Test
    void cipherRejectsMalformedKeysAndStoredValues() {
        assertFalse(new ExternalMailSecretCipher(null).configured());
        assertFalse(new ExternalMailSecretCipher("not-base64!").configured());
        assertFalse(new ExternalMailSecretCipher(Base64.getEncoder().encodeToString(new byte[31])).configured());

        byte[] key = new byte[32];
        key[0] = 4;
        ExternalMailSecretCipher cipher = new ExternalMailSecretCipher(
                "  " + Base64.getEncoder().encodeToString(key) + " ");
        assertTrue(cipher.configured());
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("no-dot"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("%%%.%%%"));
        String stored = cipher.encrypt("secret");
        String tampered = stored.substring(0, stored.length() - 2) + "AA";
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> new ExternalMailSecretCipher("").decrypt(stored)).getStatusCode());
    }

    @Test
    void clientExceptionTypesKeepCategoryTemporaryFlagAndCause() {
        RuntimeException cause = new RuntimeException("root");
        var error = new ExternalMailClient.ExternalMailClientException(
                ExternalMailClient.ExternalMailClientException.Category.NETWORK, true, "temporary", cause);
        assertEquals(ExternalMailClient.ExternalMailClientException.Category.NETWORK, error.category());
        assertTrue(error.temporary());
        assertSame(cause, error.getCause());
        assertSame(cause, new ExternalMailClient.StopFolderException(cause).getCause());
    }

    private static ExternalMailEndpointPolicy policy(boolean allowCustom, boolean allowPrivate, boolean allowPlain) {
        return new ExternalMailEndpointPolicy(new ExternalMailProperties(
                true, allowCustom, allowPrivate, allowPlain, 10, 200, 200, 15, 60, 2));
    }
}
