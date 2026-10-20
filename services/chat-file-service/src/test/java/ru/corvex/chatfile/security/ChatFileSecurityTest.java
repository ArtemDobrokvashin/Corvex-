package ru.corvex.chatfile.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFileSecurityTest {

    private static final UUID USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void principalIsBuiltFromJwtSubjectAndPermissions() {
        ChatFilePrincipal principal = ChatFilePrincipal.from(jwt(USER_ID.toString()));

        assertEquals(USER_ID, principal.userId());
        assertTrue(principal.has(ChatFilePrincipal.SEND_FILES));
        assertEquals(Set.of(
                ChatFilePrincipal.READ,
                ChatFilePrincipal.WRITE,
                ChatFilePrincipal.SEND_FILES), principal.permissions());
    }

    @Test
    void principalRejectsNonUuidSubject() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> ChatFilePrincipal.from(jwt("alice")));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    @Test
    void internalTokenGuardFailsClosed() {
        InternalTokenGuard unconfigured = new InternalTokenGuard("");
        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> unconfigured.verify("secret"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, missing.getStatusCode());

        InternalTokenGuard guard = new InternalTokenGuard("secret");
        guard.verify("secret");
        ResponseStatusException invalid = assertThrows(ResponseStatusException.class,
                () -> guard.verify("wrong"));
        assertEquals(HttpStatus.UNAUTHORIZED, invalid.getStatusCode());
    }

    private Jwt jwt(String subject) {
        Instant now = Instant.parse("2026-10-20T09:00:00Z");
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim("permissions", List.of(
                        ChatFilePrincipal.READ,
                        ChatFilePrincipal.WRITE,
                        ChatFilePrincipal.SEND_FILES))
                .build();
    }
}
