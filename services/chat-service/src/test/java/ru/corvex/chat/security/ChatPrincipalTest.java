package ru.corvex.chat.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatPrincipalTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");

    @Test
    void compactConstructorCopiesPermissionsAndAcceptsNull() {
        Set<String> source = new HashSet<>(Set.of(ChatPrincipal.READ));
        ChatPrincipal principal = new ChatPrincipal(USER_ID, source, null);
        source.add(ChatPrincipal.WRITE);

        assertEquals(Set.of(ChatPrincipal.READ), principal.permissions());
        assertEquals(Set.of(), new ChatPrincipal(USER_ID, null, null).permissions());
        assertThrows(UnsupportedOperationException.class,
                () -> principal.permissions().add(ChatPrincipal.WRITE));
    }

    @Test
    void permissionNamesRemainStable() {
        assertEquals("CHAT_READ", ChatPrincipal.READ);
        assertEquals("CHAT_WRITE", ChatPrincipal.WRITE);
        assertEquals("CHAT_CREATE_GROUP", ChatPrincipal.CREATE_GROUP);
        assertEquals("CHAT_CREATE_CHANNEL", ChatPrincipal.CREATE_CHANNEL);
        assertEquals("CHAT_MODERATE", ChatPrincipal.MODERATE);
        assertEquals("CHAT_SEND_FILES", ChatPrincipal.SEND_FILES);
        assertEquals("CHAT_VOICE_VIDEO", ChatPrincipal.VOICE_VIDEO);
    }

    @Test
    void fromJwtUsesSubjectAndPermissionsClaim() {
        Jwt jwt = jwt(USER_ID.toString(), List.of(ChatPrincipal.READ, ChatPrincipal.WRITE));

        ChatPrincipal principal = ChatPrincipal.from(jwt);

        assertEquals(USER_ID, principal.userId());
        assertEquals(Set.of(ChatPrincipal.READ, ChatPrincipal.WRITE), principal.permissions());
        assertEquals(jwt, principal.jwt());
        assertTrue(principal.has(ChatPrincipal.READ));
        assertFalse(principal.has(ChatPrincipal.MODERATE));
        principal.require(ChatPrincipal.WRITE);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> principal.require(ChatPrincipal.MODERATE)).getStatusCode());
    }

    @Test
    void fromJwtRejectsMissingOrNonUuidSubject() {
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> ChatPrincipal.from((Jwt) null)).getStatusCode());

        Jwt noSubject = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("scope", "chat")
                .build();
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> ChatPrincipal.from(noSubject)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> ChatPrincipal.from(jwt("not-a-uuid", List.of()))).getStatusCode());
    }

    @Test
    void fromAuthenticationHandlesJwtTokenJwtPrincipalAndAuthorities() {
        Jwt jwt = jwt(USER_ID.toString(), List.of(ChatPrincipal.READ));
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("IGNORED")));
        assertEquals(Set.of(ChatPrincipal.READ), ChatPrincipal.from(token).permissions());

        UsernamePasswordAuthenticationToken jwtPrincipal = new UsernamePasswordAuthenticationToken(
                jwt, null, List.of(new SimpleGrantedAuthority("IGNORED")));
        assertEquals(Set.of(ChatPrincipal.READ), ChatPrincipal.from(jwtPrincipal).permissions());

        UsernamePasswordAuthenticationToken generic = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null,
                List.of(new SimpleGrantedAuthority(ChatPrincipal.READ),
                        new SimpleGrantedAuthority(ChatPrincipal.WRITE)));
        ChatPrincipal fromAuthorities = ChatPrincipal.from(generic);
        assertEquals(Set.of(ChatPrincipal.READ, ChatPrincipal.WRITE), fromAuthorities.permissions());
        assertNull(fromAuthorities.jwt());
    }

    @Test
    void fromAuthenticationRejectsMissingAuthenticationAndBadNames() {
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> ChatPrincipal.from((Authentication) null)).getStatusCode());

        Authentication anonymous = mock(Authentication.class);
        when(anonymous.getPrincipal()).thenReturn("anonymous");
        when(anonymous.getName()).thenReturn(null);
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> ChatPrincipal.from(anonymous)).getStatusCode());

        UsernamePasswordAuthenticationToken badName = new UsernamePasswordAuthenticationToken(
                "not-a-uuid", null, List.of(new SimpleGrantedAuthority(ChatPrincipal.READ)));
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> ChatPrincipal.from(badName)).getStatusCode());
    }

    private Jwt jwt(String subject, List<String> permissions) {
        Instant now = Instant.parse("2026-10-20T09:00:00Z");
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900));
        if (!permissions.isEmpty()) {
            builder.claim("permissions", permissions);
        }
        return builder.build();
    }
}
