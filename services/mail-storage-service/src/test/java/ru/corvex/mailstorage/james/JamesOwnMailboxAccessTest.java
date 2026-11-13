package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import ru.corvex.mailstorage.client.AuthUserClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JamesOwnMailboxAccessTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID owner = UUID.randomUUID();
    private final AuthUserClient users = mock(AuthUserClient.class);
    private final JamesMailboxRegistry registry = mock(JamesMailboxRegistry.class);
    private final JamesOwnMailboxReader reader = mock(JamesOwnMailboxReader.class);
    private final JamesOwnMailboxAccess access = new JamesOwnMailboxAccess(users, registry, reader, clock);

    private JwtAuthenticationToken token(String subject, String issuer, Instant expires, boolean permission) {
        Jwt jwt = Jwt.withTokenValue("validated-by-spring-in-real-use").header("alg", "RS256")
                .subject(subject).claim("iss", issuer).issuedAt(NOW.minusSeconds(5)).expiresAt(expires).build();
        return new JwtAuthenticationToken(jwt, permission ? List.of(new SimpleGrantedAuthority("MAIL_READ_OWN")) : List.of());
    }
    private void provision() throws Exception {
        when(users.findById(owner)).thenReturn(Optional.of(new AuthUserClient.MailboxOwner(owner, "one@example.test", "One", "ACTIVE", 1)));
        when(registry.find(owner)).thenReturn(Optional.of(new JamesMailboxBinding.ProvisionedMailbox(owner, true, "one@james.local", "account-1")));
        when(reader.listFolders(any(), any())).thenReturn(new ObjectMapper().createArrayNode());
    }

    @Test void validPrincipalUsesOnlyServerLookups() throws Exception {
        provision();
        assertTrue(access.listOwnFolders(token(owner.toString(), "corvex-auth", NOW.plusSeconds(60), true)).isArray());
        verify(users).findById(owner);
        verify(registry).find(owner);
        verify(reader).listFolders(argThat(p -> p.verifiedOwnerId().equals(owner)),
                argThat(m -> m.ownerId().equals(owner) && m.accountId().equals("account-1")));
    }

    @Test void rejectsNonJwtWrongIssuerExpiredAndNoPermissionBeforeLookup() {
        assertThrows(SecurityException.class, () -> access.listOwnFolders(new TestingAuthenticationToken("u", "p")));
        assertThrows(SecurityException.class, () -> access.listOwnFolders(token(owner.toString(), "other", NOW.plusSeconds(60), true)));
        assertThrows(SecurityException.class, () -> access.listOwnFolders(token(owner.toString(), "corvex-auth", NOW.minusSeconds(1), true)));
        assertThrows(SecurityException.class, () -> access.listOwnFolders(token(owner.toString(), "corvex-auth", NOW.plusSeconds(60), false)));
        assertThrows(SecurityException.class, () -> access.listOwnFolders(token("not-a-uuid", "corvex-auth", NOW.plusSeconds(60), true)));
        verifyNoInteractions(users, registry, reader);
    }

    @Test void rejectsDisabledMissingOrMismatchedDirectoryUser() throws Exception {
        var auth = token(owner.toString(), "corvex-auth", NOW.plusSeconds(60), true);
        when(users.findById(owner)).thenReturn(Optional.empty(),
                Optional.of(new AuthUserClient.MailboxOwner(owner, "", "", "DISABLED", 0)),
                Optional.of(new AuthUserClient.MailboxOwner(UUID.randomUUID(), "", "", "ACTIVE", 0)));
        for (int i = 0; i < 3; i++) assertThrows(SecurityException.class, () -> access.listOwnFolders(auth));
        verifyNoInteractions(registry, reader);
    }

    @Test void rejectsDisabledMissingAndForeignBindingBeforeAnyTokenIsIssued() throws Exception {
        when(users.findById(owner)).thenReturn(Optional.of(new AuthUserClient.MailboxOwner(owner, "", "", "ACTIVE", 0)));
        when(registry.find(owner)).thenReturn(Optional.empty(),
                Optional.of(new JamesMailboxBinding.ProvisionedMailbox(owner, false, "one@james.local", "a")),
                Optional.of(new JamesMailboxBinding.ProvisionedMailbox(UUID.randomUUID(), true, "other@james.local", "b")));
        var auth = token(owner.toString(), "corvex-auth", NOW.plusSeconds(60), true);
        for (int i = 0; i < 3; i++) assertThrows(SecurityException.class, () -> access.listOwnFolders(auth));
        verifyNoInteractions(reader);
    }

    @Test void directoryOrRegistryFailureDoesNotFallBack() throws Exception {
        var auth = token(owner.toString(), "corvex-auth", NOW.plusSeconds(60), true);
        when(users.findById(owner)).thenThrow(new IllegalStateException("directory unavailable"));
        assertThrows(IllegalStateException.class, () -> access.listOwnFolders(auth));
        verifyNoInteractions(registry, reader);
        doReturn(Optional.of(new AuthUserClient.MailboxOwner(owner, "", "", "ACTIVE", 0))).when(users).findById(owner);
        when(registry.find(owner)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> access.listOwnFolders(auth));
        verifyNoInteractions(reader);
    }
}
