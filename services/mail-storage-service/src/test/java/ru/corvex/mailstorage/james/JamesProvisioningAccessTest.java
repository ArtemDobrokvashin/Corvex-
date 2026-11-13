package ru.corvex.mailstorage.james;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import ru.corvex.mailstorage.client.AuthUserClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mocked access controls only; no actual JWT signature or directory calls. */
class JamesProvisioningAccessTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final UUID actor = UUID.randomUUID(), owner = UUID.randomUUID();
    private final AuthUserClient users = mock(AuthUserClient.class);
    private final CurrentProvisioningPermissionLookup permissions = mock(CurrentProvisioningPermissionLookup.class);
    private final JamesProvisioningCoordinator coordinator = mock(JamesProvisioningCoordinator.class);
    private final JamesProvisioningAccess access = new JamesProvisioningAccess(users, permissions, coordinator,
            Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());

    @BeforeEach void setup() {
        when(users.findById(actor)).thenReturn(Optional.of(new AuthUserClient.MailboxOwner(actor, "a@example.test", "", "ACTIVE", 0)));
        when(users.findById(owner)).thenReturn(Optional.of(new AuthUserClient.MailboxOwner(owner, "b@example.test", "", "ACTIVE", 0)));
        when(permissions.canManageUsers(actor)).thenReturn(true);
    }

    private JwtAuthenticationToken token(String subject, String issuer, Instant expires, boolean allowed) {
        var jwt = Jwt.withTokenValue("verified-by-spring-in-real-use").header("alg", "RS256")
                .subject(subject).claim("iss", issuer).issuedAt(NOW.minusSeconds(1)).expiresAt(expires).build();
        return new JwtAuthenticationToken(jwt, allowed ? List.of(new SimpleGrantedAuthority("ADMIN_USERS_MANAGE")) : List.of());
    }
    private JwtAuthenticationToken authorized() { return token(actor.toString(), "corvex-auth", NOW.plusSeconds(60), true); }

    @Test void authorizedAccessGeneratesRandomCredentialInternally() throws Exception {
        access.create(authorized(), owner);
        verify(coordinator).create(eq(owner), eq(actor), argThat(s -> s != null && s.matches("[A-Za-z0-9_-]{43}")));
        verify(permissions).canManageUsers(actor);
    }
    @Test void invalidOrExpiredJwtNeverConsultsDirectory() {
        assertThrows(SecurityException.class, () -> access.create(new TestingAuthenticationToken("x", "x"), owner));
        assertThrows(SecurityException.class, () -> access.create(token(actor.toString(), "other", NOW.plusSeconds(60), true), owner));
        assertThrows(SecurityException.class, () -> access.create(token(actor.toString(), "corvex-auth", NOW.minusSeconds(1), true), owner));
        assertThrows(SecurityException.class, () -> access.create(token(actor.toString(), "corvex-auth", NOW.plusSeconds(60), false), owner));
        assertThrows(SecurityException.class, () -> access.create(token("invalid", "corvex-auth", NOW.plusSeconds(60), true), owner));
        verifyNoInteractions(users, permissions, coordinator);
    }
    @Test void blockedActorOrRevokedRightNeverReachesCoordinator() {
        when(users.findById(actor)).thenReturn(Optional.of(new AuthUserClient.MailboxOwner(actor, "", "", "DISABLED", 0)));
        assertThrows(SecurityException.class, () -> access.create(authorized(), owner));
        when(users.findById(actor)).thenReturn(Optional.of(new AuthUserClient.MailboxOwner(actor, "", "", "ACTIVE", 0)));
        when(permissions.canManageUsers(actor)).thenReturn(false).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(SecurityException.class, () -> access.create(authorized(), owner));
        assertThrows(IllegalStateException.class, () -> access.create(authorized(), owner));
        verifyNoInteractions(coordinator);
        verify(users, never()).findById(owner);
    }
    @Test void missingInactiveOrWrongOwnerNeverCreates() {
        when(users.findById(owner)).thenReturn(Optional.empty(),
                Optional.of(new AuthUserClient.MailboxOwner(owner, "", "", "BLOCKED", 0)),
                Optional.of(new AuthUserClient.MailboxOwner(UUID.randomUUID(), "", "", "ACTIVE", 0)));
        for (int i=0; i<3; i++) assertThrows(SecurityException.class, () -> access.create(authorized(), owner));
        verifyNoInteractions(coordinator);
    }
}
