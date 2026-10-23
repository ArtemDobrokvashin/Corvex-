package ru.corvex.chat.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InternalTokenGuardTest {

    @Test
    void acceptsOnlyExactToken() {
        InternalTokenGuard guard = new InternalTokenGuard("s3cr3t-value");

        guard.verify("s3cr3t-value");
        assertEquals("s3cr3t-value", guard.tokenOrFail());
        assertEquals("X-Corvex-Internal-Token", InternalTokenGuard.HEADER);

        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> guard.verify(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> guard.verify("s3cr3t-value ")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> guard.verify("wrong")).getStatusCode());
    }

    @Test
    void missingConfigurationFailsClosed() {
        for (String expected : new String[] {null, "", "   "}) {
            InternalTokenGuard guard = new InternalTokenGuard(expected);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                    () -> guard.verify("anything")).getStatusCode());
            assertThrows(IllegalStateException.class, guard::tokenOrFail);
        }
    }
}
