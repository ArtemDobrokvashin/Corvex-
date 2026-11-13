package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Read-only integration check, opt-in only. Requires a separate disposable
 * James with JWT enabled and a locally trusted HTTPS endpoint. Never use a
 * real key, real users, production server or production trust store.
 */
@EnabledIfEnvironmentVariable(named = "JAMES_LIVE_CONFIRM", matches = "synthetic-only")
class JamesLiveMailboxTest {
    private static final String MAIL = "urn:ietf:params:jmap:mail";
    private static final String USER1 = "user01@james.local";
    private static final String USER2 = "user02@james.local";
    private final ObjectMapper mapper = new ObjectMapper();

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing lab variable " + name);
        return value;
    }

    @Test void syntheticAccountsUseSignedJwtAndCannotReadEachOther() throws Exception {
        URI origin = URI.create(required("JAMES_LIVE_HTTPS_ORIGIN"));
        if (!"https".equalsIgnoreCase(origin.getScheme())
                || !("localhost".equalsIgnoreCase(origin.getHost()) || "127.0.0.1".equals(origin.getHost()))) {
            throw new IllegalArgumentException("Only a loopback HTTPS synthetic lab is permitted");
        }
        byte[] pkcs8 = Files.readAllBytes(Path.of(required("JAMES_LIVE_TEST_KEY_PKCS8")));
        var privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        var issuer = new JamesReadTokenIssuer(privateKey, Clock.systemUTC(), mapper);
        var client = new JamesJmapReadClient(origin, mapper);
        UUID owner1 = UUID.randomUUID();
        UUID owner2 = UUID.randomUUID();
        var principal1 = new JamesMailboxBinding.Principal(owner1, Set.of("MAIL_READ_OWN"));
        var principal2 = new JamesMailboxBinding.Principal(owner2, Set.of("MAIL_READ_OWN"));
        // A synthetic placeholder is used only to obtain the session. Real mapping
        // must be provisioned server-side and independently checked before use.
        String jwt1 = issuer.issue(principal1, new JamesMailboxBinding.ProvisionedMailbox(owner1, true, USER1, "synthetic"));
        String jwt2 = issuer.issue(principal2, new JamesMailboxBinding.ProvisionedMailbox(owner2, true, USER2, "synthetic"));
        var account1Node = client.session(jwt1).path("primaryAccounts").path(MAIL);
        var account2Node = client.session(jwt2).path("primaryAccounts").path(MAIL);
        assertTrue(account1Node.isTextual() && !account1Node.asText().isBlank());
        assertTrue(account2Node.isTextual() && !account2Node.asText().isBlank());
        String account1 = account1Node.asText();
        String account2 = account2Node.asText();
        assertNotEquals(account1, account2);
        var reader = new JamesOwnMailboxReader(issuer, client, mapper);
        var liveFolders1 = reader.listFolders(principal1,
                new JamesMailboxBinding.ProvisionedMailbox(owner1, true, USER1, account1));
        assertTrue(liveFolders1.isArray());
        var folderMapper = new JamesFolderMapper();
        var mapped = folderMapper.map(owner1, account1, liveFolders1);
        assertEquals(liveFolders1.size(), mapped.size());
        if (!mapped.isEmpty()) {
            UUID folderId = mapped.get(0).id();
            assertTrue(folderMapper.resolveNativeId(owner1, account1, liveFolders1, folderId).isPresent());
            assertTrue(folderMapper.resolveNativeId(owner2, account1, liveFolders1, folderId).isEmpty());
        }
        assertTrue(reader.listFolders(principal2,
                new JamesMailboxBinding.ProvisionedMailbox(owner2, true, USER2, account2)).isArray());
        var inbox = mapped.stream().filter(f -> "INBOX".equals(f.specialUse())).findFirst()
                .orElseThrow(() -> new AssertionError("Synthetic inbox missing"));
        JsonNode page = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            page = reader.firstMessagePage(principal1,
                    new JamesMailboxBinding.ProvisionedMailbox(owner1, true, USER1, account1),
                    inbox.id(), 25, folderMapper);
            if (!page.isEmpty()) break;
            Thread.sleep(500);
        }
        assertNotNull(page);
        boolean found = false;
        for (JsonNode message : page) {
            if ("Corvex James live synthetic message".equals(message.path("subject").asText())) found = true;
        }
        assertTrue(found, "Synthetic IMAP message not found by Java JMAP reader");
        var first = reader.messagePage(principal1,
                new JamesMailboxBinding.ProvisionedMailbox(owner1, true, USER1, account1),
                inbox.id(), 1, folderMapper, 0, null);
        assertEquals(1, first.items().size());
        var previews = new JamesMessagePreviewMapper().map(owner1, account1, inbox.id(), first.items());
        assertEquals("Corvex James live synthetic message", previews.get(0).subject());
        assertEquals("UNVERIFIED", previews.get(0).avStatus());
        assertTrue(previews.get(0).hasAttachments());
        assertEquals(1, first.nextPosition().intValue());
        var second = reader.messagePage(principal1,
                new JamesMailboxBinding.ProvisionedMailbox(owner1, true, USER1, account1),
                inbox.id(), 1, folderMapper, first.nextPosition(), first.queryState());
        assertTrue(second.items().isEmpty());
        assertThrows(SecurityException.class, () -> reader.firstMessagePage(principal2,
                new JamesMailboxBinding.ProvisionedMailbox(owner2, true, USER2, account2),
                inbox.id(), 25, folderMapper));
        assertThrows(java.io.IOException.class, () -> client.read(jwt2, "Mailbox/get", mapper.createObjectNode().put("accountId", account1)));
        assertThrows(SecurityException.class, () -> reader.listFolders(principal1,
                new JamesMailboxBinding.ProvisionedMailbox(owner1, true, USER1, account2)));
        var wrongKeyGen = KeyPairGenerator.getInstance("RSA");
        wrongKeyGen.initialize(2048);
        var wrongIssuer = new JamesReadTokenIssuer((RSAPrivateKey) wrongKeyGen.generateKeyPair().getPrivate(), Clock.systemUTC(), mapper);
        var fake = wrongIssuer.issue(principal1, new JamesMailboxBinding.ProvisionedMailbox(owner1, true, USER1, account1));
        assertThrows(java.io.IOException.class, () -> client.session(fake));
        var expiredIssuer = new JamesReadTokenIssuer(privateKey, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-5)), mapper);
        var expired = expiredIssuer.issue(principal1, new JamesMailboxBinding.ProvisionedMailbox(owner1, true, USER1, account1));
        assertThrows(java.io.IOException.class, () -> client.session(expired));
    }
}
