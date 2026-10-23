package ru.corvex.mailstorage.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageSecurityTest {

    @Test
    void tokenGuardAcceptsOnlyExactConfiguredToken() {
        InternalTokenGuard guard = new InternalTokenGuard("expected-secret");

        assertDoesNotThrow(() -> guard.verify("expected-secret"));
        assertEquals("expected-secret", guard.tokenOrFail());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> guard.verify("wrong")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> guard.verify(null)).getStatusCode());
    }

    @Test
    void tokenGuardFailsClosedWhenSecretIsNotConfigured() {
        for (String missing : new String[]{null, "", " "}) {
            InternalTokenGuard guard = new InternalTokenGuard(missing);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                    () -> guard.verify("anything")).getStatusCode());
            assertThrows(IllegalStateException.class, guard::tokenOrFail);
        }
    }

    @Test
    void jwtPermissionsBecomeAuthorities() {
        StorageSecurityConfig config = new StorageSecurityConfig();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user")
                .claim("permissions", List.of("MAIL_READ_OWN", "MAIL_SEND_INTERNAL"))
                .build();

        var authentication = config.jwtAuthenticationConverter().convert(jwt);

        assertEquals(List.of("MAIL_READ_OWN", "MAIL_SEND_INTERNAL"),
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
    }

    @Test
    void missingPermissionsClaimProducesNoAuthorities() {
        StorageSecurityConfig config = new StorageSecurityConfig();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user")
                .build();

        assertEquals(List.of(), config.jwtAuthenticationConverter().convert(jwt).getAuthorities());
    }
}
