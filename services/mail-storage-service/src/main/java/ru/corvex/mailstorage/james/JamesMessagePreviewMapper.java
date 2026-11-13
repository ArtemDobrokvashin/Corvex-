package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Experimental JMAP -> UI-ready preview. No fake IMAP UID or AV result.
 * Input must be produced by the authorized, folder-scoped reader, not a browser.
 */
final class JamesMessagePreviewMapper {
    record Preview(UUID id, UUID folderId, String sender, String subject, String snippet,
                   Instant date, long sizeBytes, boolean seen, boolean flagged,
                   boolean answered, boolean hasAttachments, String avStatus) { }

    List<Preview> map(UUID owner, String account, UUID folderId, JsonNode emails) throws IOException {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(folderId);
        if (account == null || account.isBlank() || account.length() > 1024 || emails == null || !emails.isArray()) {
            throw new IOException("Invalid James email page");
        }
        var views = new ArrayList<Preview>();
        var ids = new HashSet<UUID>();
        for (JsonNode item : emails) {
            if (!item.isObject()) throw new IOException("Invalid James email");
            String nativeId = requiredText(item, "id", 1024);
            UUID id = messageId(owner, account, nativeId);
            if (!ids.add(id)) throw new IOException("Duplicate James email id");
            String subject = optionalText(item, "subject", 8192);
            String snippet = optionalText(item, "preview", 8192);
            String received = requiredText(item, "receivedAt", 128);
            final Instant date;
            try { date = Instant.parse(received); }
            catch (DateTimeParseException e) { throw new IOException("Invalid James receivedAt", e); }
            JsonNode size = item.path("size");
            JsonNode attachment = item.path("hasAttachment");
            JsonNode keywords = item.path("keywords");
            if (!size.isIntegralNumber() || !size.canConvertToLong() || size.longValue() < 0
                    || !attachment.isBoolean() || !keywords.isObject()) {
                throw new IOException("Invalid James email properties");
            }
            var values = keywords.elements();
            while (values.hasNext()) {
                JsonNode value = values.next();
                if (!value.isBoolean() || !value.booleanValue()) throw new IOException("Invalid James keyword");
            }
            views.add(new Preview(id, folderId, sender(item.path("from")), subject, snippet, date, size.longValue(),
                    keywords.path("$seen").asBoolean(false), keywords.path("$flagged").asBoolean(false),
                    keywords.path("$answered").asBoolean(false), attachment.booleanValue(), "UNVERIFIED"));
        }
        return List.copyOf(views);
    }

    private static String sender(JsonNode from) throws IOException {
        if (from.isNull()) return "";
        if (!from.isArray()) throw new IOException("Invalid James sender");
        if (from.isEmpty()) return "";
        JsonNode address = from.get(0);
        if (!address.isObject()) throw new IOException("Invalid James sender");
        String email = requiredText(address, "email", 1024);
        String name = optionalText(address, "name", 1024);
        return name.isBlank() ? email : name + " <" + email + ">";
    }

    private static String requiredText(JsonNode node, String key, int limit) throws IOException {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > limit) {
            throw new IOException("Invalid James " + key);
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String key, int limit) throws IOException {
        JsonNode value = node.path(key);
        if (value.isNull()) return "";
        if (!value.isTextual() || value.textValue().length() > limit) throw new IOException("Invalid James " + key);
        return value.textValue();
    }

    /** Stable account-scoped message ID, deliberately independent of the folder. */
    private static UUID messageId(UUID owner, String account, String nativeId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{"corvex-james-message-v1", owner.toString(), account, nativeId}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            byte[] hash = digest.digest();
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x80);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
