package ru.corvex.mailimap.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Полный клиент внутреннего протокольного API mail-storage-service для будущего
 * James MailboxManager-адаптера: папки (CREATE/DELETE/RENAME/STATUS), списки UID,
 * сырые тела, флаги, EXPUNGE, APPEND, COPY/MOVE, QUOTA. Ошибки хранилища не маскируются:
 * 404 проксируется как {@link NotFoundException} (IMAP NO [NONEXISTENT]), остальные —
 * IOException (временный NO клиенту, без потери состояния сессии).
 */
@Component
public class ImapStorageClient {

    private final String storageBaseUrl;
    private final String internalToken;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ImapStorageClient(
            @Value("${corvex.storage.base-url:${CORVEX_STORAGE_BASE_URL:http://mail-storage-service:8085}}") String storageBaseUrl,
            @Value("${corvex.security.internal-token:${CORVEX_INTERNAL_API_TOKEN:}}") String internalToken,
            ObjectMapper mapper) {
        this.storageBaseUrl = storageBaseUrl.replaceAll("/$", "");
        this.internalToken = internalToken;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public static class NotFoundException extends IOException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public record FolderInfo(UUID id, UUID parentId, String name, String specialUse,
                             long uidNext, long uidValidity, long messageCount, long unseenCount) {
    }

    public record MessageMeta(UUID id, long uid, String messageIdHeader, String sender,
                              String subject, Instant internalDate, long sizeBytes,
                              boolean seen, boolean answered, boolean flagged,
                              boolean draft, boolean deleted) {
    }

    public record FlagsUpdate(Boolean seen, Boolean answered, Boolean flagged, Boolean draft, Boolean deleted) {
    }

    public record AppendResponse(UUID messageId, long uid) {
    }

    public record CopyResponse(List<Long> sourceUids, List<Long> targetUids) {
    }

    public record QuotaView(long usedBytes, long quotaBytes, long messageCount) {
    }

    // ---------------------------------------------------------------- folders

    public List<FolderInfo> listFolders(UUID userId) throws IOException {
        return readJson(send(req(userBase(userId) + "/folders").GET().build()), new TypeReference<>() {
        });
    }

    public FolderInfo createFolder(UUID userId, String name, UUID parentId) throws IOException {
        var body = mapper.createObjectNode().put("name", name);
        if (parentId != null) {
            body.put("parentId", parentId.toString());
        }
        HttpRequest request = req(userBase(userId) + "/folders")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return readJson(send(request), new TypeReference<>() {
        });
    }

    public FolderInfo renameFolder(UUID userId, UUID folderId, String name) throws IOException {
        var body = mapper.createObjectNode().put("name", name);
        HttpRequest request = req(userBase(userId) + "/folders/" + folderId)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return readJson(send(request), new TypeReference<>() {
        });
    }

    public void deleteFolder(UUID userId, UUID folderId) throws IOException {
        HttpResponse<String> response = send(req(userBase(userId) + "/folders/" + folderId).DELETE().build());
        if (response.statusCode() == 404) {
            throw new NotFoundException("Папка не найдена: " + folderId);
        }
        if (response.statusCode() != 204) {
            throw new IOException("mail-storage deleteFolder вернул HTTP " + response.statusCode());
        }
    }

    public FolderInfo folderStatus(UUID userId, UUID folderId) throws IOException {
        return readJson(send(req(userBase(userId) + "/folders/" + folderId + "/status").GET().build()),
                new TypeReference<>() {
                });
    }

    // ---------------------------------------------------------------- messages

    public List<MessageMeta> listMessages(UUID userId, UUID folderId, long afterUid, int limit) throws IOException {
        String url = userBase(userId) + "/folders/" + folderId + "/messages?afterUid=" + afterUid + "&limit=" + limit;
        return readJson(send(req(url).GET().build()), new TypeReference<>() {
        });
    }

    public InputStream openRawByUid(UUID userId, UUID folderId, long uid) throws IOException {
        HttpResponse<InputStream> response;
        try {
            response = http.send(
                    req(userBase(userId) + "/folders/" + folderId + "/messages/" + uid + "/raw").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос к mail-storage прерван", e);
        }
        if (response.statusCode() == 404) {
            response.body().close();
            throw new NotFoundException("Письмо не найдено: uid=" + uid);
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("mail-storage openRawByUid вернул HTTP " + response.statusCode());
        }
        return response.body();
    }

    public MessageMeta updateFlags(UUID userId, UUID folderId, long uid, FlagsUpdate flags) throws IOException {
        HttpRequest request = req(userBase(userId) + "/folders/" + folderId + "/messages/" + uid + "/flags")
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(flags)))
                .build();
        return readJson(send(request), new TypeReference<>() {
        });
    }

    public List<Long> expunge(UUID userId, UUID folderId, List<Long> uids) throws IOException {
        var body = mapper.createObjectNode();
        var arr = body.putArray("uids");
        if (uids != null) {
            uids.forEach(arr::add);
        }
        HttpRequest request = req(userBase(userId) + "/folders/" + folderId + "/expunge")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        record ExpungeResponse(List<Long> expungedUids) {
        }
        ExpungeResponse res = readJson(send(request), new TypeReference<>() {
        });
        return res.expungedUids();
    }

    public AppendResponse append(UUID userId, UUID folderId, byte[] eml, Instant internalDate, String flags)
            throws IOException {
        HttpRequest.Builder request = req(userBase(userId) + "/folders/" + folderId + "/messages")
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(eml));
        if (internalDate != null) {
            request.header("X-Corvex-Internal-Date", internalDate.toString());
        }
        if (flags != null && !flags.isBlank()) {
            request.header("X-Corvex-Flags", flags);
        }
        return readJson(send(request.build()), new TypeReference<>() {
        });
    }

    public CopyResponse copyMessages(UUID userId, UUID folderId, List<Long> uids, UUID targetFolderId, boolean move)
            throws IOException {
        var body = mapper.createObjectNode()
                .put("targetFolderId", targetFolderId.toString())
                .put("move", move);
        var arr = body.putArray("uids");
        uids.forEach(arr::add);
        HttpRequest request = req(userBase(userId) + "/folders/" + folderId + "/copy")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return readJson(send(request), new TypeReference<>() {
        });
    }

    public QuotaView quota(UUID userId) throws IOException {
        return readJson(send(req(userBase(userId) + "/quota").GET().build()), new TypeReference<>() {
        });
    }

    // ---------------------------------------------------------------- plumbing

    private String userBase(UUID userId) {
        return storageBaseUrl + "/internal/protocol/users/" + userId;
    }

    private HttpRequest.Builder req(String url) throws IOException {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IOException("CORVEX_INTERNAL_API_TOKEN не настроен");
        }
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("X-Corvex-Internal-Token", internalToken);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос к mail-storage прерван", e);
        }
    }

    private <T> T readJson(HttpResponse<String> response, TypeReference<T> type) throws IOException {
        if (response.statusCode() == 404) {
            throw new NotFoundException("Объект не найден в mail-storage");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("mail-storage вернул HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), type);
    }
}
