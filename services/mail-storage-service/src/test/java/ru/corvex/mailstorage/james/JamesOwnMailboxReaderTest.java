package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JamesOwnMailboxReaderTest {
    private static KeyPair keys;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID owner = UUID.randomUUID();
    private final JamesMailboxBinding.Principal principal = new JamesMailboxBinding.Principal(owner, Set.of("MAIL_READ_OWN"));
    private final JamesMailboxBinding.ProvisionedMailbox mapping = new JamesMailboxBinding.ProvisionedMailbox(owner, true, "one@james.local", "account-1");
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeAll static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    private JamesReadTokenIssuer issuer() {
        return new JamesReadTokenIssuer((RSAPrivateKey) keys.getPrivate(), clock, mapper);
    }

    @Test void dedicatedTokenContainsJamesLoginAndShortExpiryAndVerifies() throws Exception {
        String token = issuer().issue(principal, mapping);
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);
        var header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        var claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("RS256", header.path("alg").asText());
        assertEquals("one@james.local", claims.path("sub").asText());
        assertEquals(clock.instant().getEpochSecond() + 60, claims.path("exp").asLong());
        assertEquals("james-jmap", claims.path("aud").asText());
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
    }

    @Test void refusesToIssueForForeignDisabledOrUnauthorisedOwner() {
        var foreign = new JamesMailboxBinding.ProvisionedMailbox(UUID.randomUUID(), true, "other@james.local", "account-2");
        var inactive = new JamesMailboxBinding.ProvisionedMailbox(owner, false, "one@james.local", "account-1");
        var noPermission = new JamesMailboxBinding.Principal(owner, Set.of());
        assertThrows(SecurityException.class, () -> issuer().issue(principal, foreign));
        assertThrows(SecurityException.class, () -> issuer().issue(principal, inactive));
        assertThrows(SecurityException.class, () -> issuer().issue(noPermission, mapping));
    }

    @Test void listsOnlyBoundAccountAndUsesIssuedToken() throws Exception {
        var client = mock(JamesJmapReadClient.class);
        when(client.session(anyString())).thenReturn(mapper.readTree("{\"primaryAccounts\":{\"urn:ietf:params:jmap:mail\":\"account-1\"}}"));
        when(client.read(anyString(), eq("Mailbox/get"), any())).thenReturn(mapper.readTree("{\"accountId\":\"account-1\",\"list\":[]}"));
        var reader = new JamesOwnMailboxReader(issuer(), client, mapper);
        assertTrue(reader.listFolders(principal, mapping).isArray());
        verify(client).read(anyString(), eq("Mailbox/get"), argThat(args -> args.path("accountId").asText().equals("account-1")));
    }

    @Test void refusesMismatchedSessionBeforeReadingAndRejectsForeignResults() throws Exception {
        var client = mock(JamesJmapReadClient.class);
        when(client.session(anyString())).thenReturn(mapper.readTree("{\"primaryAccounts\":{\"urn:ietf:params:jmap:mail\":\"account-2\"}}"));
        var reader = new JamesOwnMailboxReader(issuer(), client, mapper);
        assertThrows(SecurityException.class, () -> reader.listFolders(principal, mapping));
        verify(client, never()).read(anyString(), anyString(), any());
        when(client.session(anyString())).thenReturn(mapper.readTree("{\"primaryAccounts\":{\"urn:ietf:params:jmap:mail\":\"account-1\"}}"));
        when(client.read(anyString(), anyString(), any())).thenReturn(mapper.readTree("{\"accountId\":\"account-2\",\"list\":[]}"));
        assertThrows(java.io.IOException.class, () -> reader.listFolders(principal, mapping));
    }
}
