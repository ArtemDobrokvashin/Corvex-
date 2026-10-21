package ru.corvex.mailstorage.external;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.domain.ExternalMailAccount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalMailEndpointPolicyTest {

    private ExternalMailEndpointPolicy policy(boolean allowPrivate, boolean allowPlain) {
        return new ExternalMailEndpointPolicy(new ExternalMailProperties(
                true, true, allowPrivate, allowPlain, 10, 200, 200, 15, 60, 2));
    }

    @Test
    void allowsPublicIpLiteral() {
        var endpoint = policy(false, false).validate(
                "8.8.8.8", 993, ExternalMailAccount.Security.SSL_TLS, false);
        assertEquals("8.8.8.8", endpoint.host());
    }

    @Test
    void blocksLoopbackAndPrivateNetworks() {
        assertThrows(ResponseStatusException.class, () -> policy(false, false).validate(
                "127.0.0.1", 993, ExternalMailAccount.Security.SSL_TLS, false));
        assertThrows(ResponseStatusException.class, () -> policy(false, false).validate(
                "192.168.1.20", 993, ExternalMailAccount.Security.SSL_TLS, false));
        assertThrows(ResponseStatusException.class, () -> policy(false, false).validate(
                "169.254.169.254", 993, ExternalMailAccount.Security.SSL_TLS, false));
    }

    @Test
    void blocksPlainByDefault() {
        assertThrows(ResponseStatusException.class, () -> policy(false, false).validate(
                "8.8.8.8", 143, ExternalMailAccount.Security.PLAIN, false));
    }
}
