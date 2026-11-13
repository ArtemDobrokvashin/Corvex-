package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

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

    private record Session(String token, JamesMailboxBinding.BoundMailbox binding) { }

    private Session open(JamesMailboxBinding.Principal principal, JamesMailboxBinding.ProvisionedMailbox mapping)
            throws IOException, GeneralSecurityException, InterruptedException {
        String token = issuer.issue(principal, mapping);
        JsonNode session = client.session(token);
        JsonNode primary = session.path("primaryAccounts").path(MAIL);
        if (!primary.isTextual()) throw new SecurityException("James primary account missing");
        return new Session(token, JamesMailboxBinding.requireOwnRead(principal, mapping, primary.textValue()));
    }

    private JsonNode folders(Session session) throws IOException, InterruptedException {
        String account = session.binding().accountId();
        JsonNode result = client.read(session.token(), "Mailbox/get", mapper.createObjectNode().put("accountId", account));
        if (!account.equals(result.path("accountId").asText()) || !result.path("list").isArray()) {
            throw new IOException("Invalid James mailbox response");
        }
        return result.path("list");
    }

    JsonNode listFolders(JamesMailboxBinding.Principal principal, JamesMailboxBinding.ProvisionedMailbox mapping)
            throws IOException, GeneralSecurityException, InterruptedException {
        return folders(open(principal, mapping));
    }

    /**
     * Read a bounded first page only. The folder UUID is resolved against this
     * session's own current folders. Results are JMAP metadata, not DTOs: no
     * IMAP UID, cursor, AV status or scan provenance is invented here.
     */
    JsonNode firstMessagePage(JamesMailboxBinding.Principal principal, JamesMailboxBinding.ProvisionedMailbox mapping,
                              UUID publicFolderId, int limit, JamesFolderMapper folderMapper)
            throws IOException, GeneralSecurityException, InterruptedException {
        return messagePage(principal, mapping, publicFolderId, limit, folderMapper, 0, null).items();
    }

    record MessagePage(JsonNode items, String queryState, Integer nextPosition) { }

    /** The caller must restart from page one on changed query state. */
    MessagePage messagePage(JamesMailboxBinding.Principal principal, JamesMailboxBinding.ProvisionedMailbox mapping,
                            UUID publicFolderId, int limit, JamesFolderMapper folderMapper,
                            int position, String expectedState)
            throws IOException, GeneralSecurityException, InterruptedException {
        if (limit < 1 || limit > 100 || position < 0 || position > 1000000
                || (position == 0 && expectedState != null)
                || (position > 0 && (expectedState == null || expectedState.isBlank() || expectedState.length() > 1024))) {
            throw new IllegalArgumentException("Invalid James page request");
        }
        Objects.requireNonNull(publicFolderId);
        Objects.requireNonNull(folderMapper);
        Session session = open(principal, mapping);
        String account = session.binding().accountId();
        String folder = folderMapper.resolveNativeId(principal.verifiedOwnerId(), account, folders(session), publicFolderId)
                .orElseThrow(() -> new SecurityException("James folder not found"));
        ObjectNode query = mapper.createObjectNode().put("accountId", account).put("limit", limit).put("position", position);
        query.putObject("filter").put("inMailbox", folder);
        query.putArray("sort").addObject().put("property", "receivedAt").put("isAscending", false);
        JsonNode result = client.read(session.token(), "Email/query", query);
        JsonNode ids = result.path("ids");
        JsonNode stateNode = result.path("queryState");
        JsonNode positionNode = result.path("position");
        if (!account.equals(result.path("accountId").asText()) || !ids.isArray() || ids.size() > limit
                || !stateNode.isTextual() || stateNode.textValue().isBlank() || stateNode.textValue().length() > 1024
                || !positionNode.isIntegralNumber() || !positionNode.canConvertToInt() || positionNode.intValue() != position) {
            throw new IOException("Invalid James email query response");
        }
        String state = stateNode.textValue();
        if (expectedState != null && !expectedState.equals(state)) {
            throw new IOException("James query changed; reload from first page");
        }
        Integer nextPosition = ids.size() == limit && position <= 1000000 - limit ? position + limit : null;
        var requested = new HashSet<String>();
        for (JsonNode id : ids) {
            if (!id.isTextual() || id.textValue().isBlank() || id.textValue().length() > 1024 || !requested.add(id.textValue())) {
                throw new IOException("Invalid James email query ids");
            }
        }
        ArrayNode ordered = mapper.createArrayNode();
        if (requested.isEmpty()) return new MessagePage(ordered, state, null);
        ObjectNode get = mapper.createObjectNode().put("accountId", account);
        get.set("ids", ids.deepCopy());
        ArrayNode properties = get.putArray("properties");
        for (String property : new String[]{"id", "mailboxIds", "from", "subject", "preview", "receivedAt", "size", "keywords", "hasAttachment"}) {
            properties.add(property);
        }
        JsonNode page = client.read(session.token(), "Email/get", get);
        JsonNode emails = page.path("list");
        if (!account.equals(page.path("accountId").asText()) || !emails.isArray()
                || !page.path("notFound").isArray() || !page.path("notFound").isEmpty()
                || emails.size() != requested.size()) {
            throw new IOException("Invalid or changed James email page");
        }
        var byId = new HashMap<String, JsonNode>();
        for (JsonNode email : emails) {
            JsonNode id = email.path("id");
            JsonNode mailboxes = email.path("mailboxIds");
            if (!email.isObject() || !id.isTextual() || !requested.contains(id.textValue())
                    || !mailboxes.isObject() || !mailboxes.path(folder).isBoolean() || !mailboxes.path(folder).asBoolean()
                    || byId.putIfAbsent(id.textValue(), email) != null) {
                throw new IOException("Invalid or foreign James email");
            }
        }
        for (JsonNode id : ids) ordered.add(byId.get(id.textValue()));
        return new MessagePage(ordered, state, nextPosition);
    }
}
