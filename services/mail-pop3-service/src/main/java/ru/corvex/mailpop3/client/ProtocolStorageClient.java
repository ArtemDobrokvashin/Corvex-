package ru.corvex.mailpop3.client;

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
import java.util.Optional;
import java.util.UUID;

/**
 * Клиент внутреннего протокольного API mail-storage-service (этап 3). POP3-сервис не имеет
 * БД и не читает Maildir: INBOX, метаданные, сырые .eml и удаление — только через хранилище
 * под X-Corvex-Internal-Token. Все ошибки бэкенда — IOException наверх в James Mailbox:
 * протокол вернёт клиенту -ERR, а не молча пустой ящик.
 */
@Component
public class ProtocolStorageClient {

    private final String storageBaseUrl;
    private final String internalToken;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ProtocolStorageClient(
            @Value("${corvex.storage.base-url:${CORVEX_STORAGE_BASE_URL:http://mail-storage-service:8085}}") String storageBaseUrl,
            @Value("${corvex.security.internal-token:${CORVEX_INTERNAL_API_TOKEN:}}") String internalToken,
            ObjectMapper mapper) {
        this.storageBaseUrl = storageBaseUrl.replaceAll("/$", "");
        this.internalToken = internalToken;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public record FolderInfo(UUID id, UUID parentId, String name, String specialUse,
                             long uidNext, long uidValidity, long messageCount, long unseenCount) {
    }

    public record MessageMeta(UUID id, long uid, String messageIdHeader, String sender,
                              String subject, Instant internalDate, long sizeBytes,
                              boolean seen, boolean answered, boolean flagged,
                              boolean draft, boolean deleted) {
    }

    public List<FolderInfo> listFolders(UUID userId) throws IOException {
        HttpResponse<String> response = send(request(userBase(userId) + "/folders").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "listFolders");
        return mapper.readValue(response.body(), new TypeReference<>() {
        });
    }

    /** POP3 видит только INBOX (RFC 1939): папка с SPECIAL-USE \Inbox. */
    public Optional<FolderInfo> findInbox(UUID userId) throws IOException {
        return listFolders(userId).stream()
                .filter(f -> "INBOX".equalsIgnoreCase(f.name()) || "INBOX".equalsIgnoreCase(f.specialUse()))
                .findFirst();
    }

    public List<MessageMeta> listMessages(UUID userId, UUID folderId, long afterUid, int limit) throws IOException {
        String url = userBase(userId) + "/folders/" + folderId + "/messages?afterUid=" + afterUid + "&limit=" + limit;
        HttpResponse<String> response = send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "listMessages");
        return mapper.readValue(response.body(), new TypeReference<>() {
        });
    }

    /** Поток сырого .eml; закрывает вызывающий (James RetrCmdHandler читает до конца). */
    public InputStream openRawById(UUID userId, UUID messageId) throws IOException {
        HttpResponse<InputStream> response = send(
                request(userBase(userId) + "/messages/" + messageId + "/raw").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("mail-storage openRawById вернул HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** Жёсткое удаление: POP3 DELE применяется на QUIT, флага \Deleted в протоколе нет. */
    public void deleteMessage(UUID userId, UUID messageId) throws IOException {
        HttpResponse<String> response = send(
                request(userBase(userId) + "/messages/" + messageId).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 204 && response.statusCode() != 404) {
            throw new IOException("mail-storage deleteMessage вернул HTTP " + response.statusCode());
        }
    }

    private String userBase(UUID userId) {
        return storageBaseUrl + "/internal/protocol/users/" + userId;
    }

    private HttpRequest.Builder request(String url) throws IOException {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IOException("CORVEX_INTERNAL_API_TOKEN не настроен");
        }
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("X-Corvex-Internal-Token", internalToken);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос к mail-storage прерван", e);
        }
    }

    private static void requireSuccess(HttpResponse<?> response, String op) throws IOException {
        if (response.statusCode() != 200) {
            throw new IOException("mail-storage " + op + " вернул HTTP " + response.statusCode());
        }
    }
}
