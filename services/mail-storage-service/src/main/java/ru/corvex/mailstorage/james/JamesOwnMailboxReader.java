package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Objects;

/** Disconnected prototype: no controller, bean, key loading or trusted lookup. */
final class JamesOwnMailboxReader {
    private static final String MAIL = "urn:ietf:params:jmap:mail";
    private final JamesReadTokenIssuer issuer;
    private final JamesJmapReadClient client;
    private final ObjectMapper mapper;

    JamesOwnMailboxReader(JamesReadTokenIssuer issuer, JamesJmapReadClient client, ObjectMapper mapper) {
        this.issuer = Objects.requireNonNull(issuer);
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
    }

    JsonNode listFolders(JamesMailboxBinding.Principal principal, JamesMailboxBinding.ProvisionedMailbox mapping)
            throws IOException, GeneralSecurityException, InterruptedException {
        String token = issuer.issue(principal, mapping);
        JsonNode session = client.session(token);
        JsonNode primary = session.path("primaryAccounts").path(MAIL);
        if (!primary.isTextual()) throw new SecurityException("James primary account missing");
        var bound = JamesMailboxBinding.requireOwnRead(principal, mapping, primary.textValue());
        JsonNode result = client.read(token, "Mailbox/get", mapper.createObjectNode().put("accountId", bound.accountId()));
        if (!bound.accountId().equals(result.path("accountId").asText()) || !result.path("list").isArray()) {
            throw new IOException("Invalid James mailbox response");
        }
        return result.path("list");
    }
}
