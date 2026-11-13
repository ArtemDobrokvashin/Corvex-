package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.JsonNode;
import ru.corvex.mailstorage.api.dto.MailDtos;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Experimental JMAP Mailbox/get -> Corvex folder view mapping. This is not an
 * authorization boundary: owner and account MUST be obtained from a validated,
 * bound server-side session. Never accept a browser-supplied James account.
 * The public UUID is deterministically derived; reverse lookups MUST recompute
 * it from the current owner's authorized folder list, never from a global map.
 */
final class JamesFolderMapper {
    private static final Map<String, String> SPECIAL = Map.of(
            "inbox", "INBOX", "sent", "SENT", "drafts", "DRAFTS",
            "trash", "TRASH", "junk", "SPAM", "archive", "ARCHIVE");
    private static final String DOMAIN = "corvex-james-folder-v1";

    List<MailDtos.FolderView> map(UUID owner, String accountId, JsonNode folders) throws IOException {
        Objects.requireNonNull(owner, "owner");
        if (invalid(accountId) || folders == null || !folders.isArray()) throw new IOException("Invalid James folders");
        var result = new ArrayList<MailDtos.FolderView>();
        var nativeIds = new HashSet<String>();
        var publicIds = new HashSet<UUID>();
        for (JsonNode folder : folders) {
            if (!folder.isObject()) throw new IOException("Invalid James folder");
            String id = text(folder, "id");
            String name = text(folder, "name");
            long total = count(folder, "totalEmails");
            long unread = count(folder, "unreadEmails");
            JsonNode role = folder.path("role");
            if ((!role.isMissingNode() && !role.isNull() && !role.isTextual()) || unread > total) {
                throw new IOException("Invalid James folder properties");
            }
            UUID publicId = publicId(owner, accountId, id);
            if (!nativeIds.add(id) || !publicIds.add(publicId)) throw new IOException("Duplicate James folder id");
            String specialUse = role.isTextual() ? SPECIAL.get(role.textValue()) : null;
            result.add(new MailDtos.FolderView(publicId, name, specialUse, total, unread));
        }
        return List.copyOf(result);
    }

    /** Resolves only against a fresh, authorized Mailbox/get list for this owner. */
    Optional<String> resolveNativeId(UUID owner, String accountId, JsonNode folders, UUID publicFolderId)
            throws IOException {
        Objects.requireNonNull(publicFolderId, "publicFolderId");
        List<MailDtos.FolderView> validated = map(owner, accountId, folders);
        for (int i = 0; i < validated.size(); i++) {
            if (validated.get(i).id().equals(publicFolderId)) {
                return Optional.of(folders.get(i).path("id").textValue());
            }
        }
        return Optional.empty();
    }

    private static String text(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isTextual() || invalid(value.textValue())) throw new IOException("Invalid James folder " + field);
        return value.textValue();
    }

    private static boolean invalid(String value) { return value == null || value.isBlank() || value.length() > 1024; }

    private static long count(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IOException("Invalid James folder " + field);
        }
        return value.longValue();
    }

    private static UUID publicId(UUID owner, String account, String nativeId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{DOMAIN, owner.toString(), account, nativeId}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            byte[] hash = digest.digest();
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x80); // UUID v8, name-derived custom format.
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
