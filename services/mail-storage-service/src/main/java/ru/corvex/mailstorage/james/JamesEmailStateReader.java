package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

/**
 * Experimental account-level Email/get state probe. A changing state can
 * indicate account changes, but equality is NOT proof of a consistent search
 * index or an atomic query snapshot. Never use it alone to enable pagination.
 */
final class JamesEmailStateReader {
    private final JamesJmapReadClient client;
    private final ObjectMapper mapper;
    JamesEmailStateReader(JamesJmapReadClient client, ObjectMapper mapper) {
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
    }

    String current(String token, String account) throws IOException, InterruptedException {
        if (account == null || account.isBlank() || account.length() > 1024) {
            throw new IllegalArgumentException("Invalid account");
        }
        var arguments = mapper.createObjectNode().put("accountId", account);
        arguments.putArray("ids");
        JsonNode response = client.read(token, "Email/get", arguments);
        JsonNode state = response.path("state");
        if (!account.equals(response.path("accountId").asText()) || !state.isTextual()
                || state.textValue().isBlank() || state.textValue().length() > 1024
                || !response.path("list").isArray() || !response.path("list").isEmpty()
                || !response.path("notFound").isArray() || !response.path("notFound").isEmpty()) {
            throw new IOException("Invalid James email state response");
        }
        return state.textValue();
    }
}
