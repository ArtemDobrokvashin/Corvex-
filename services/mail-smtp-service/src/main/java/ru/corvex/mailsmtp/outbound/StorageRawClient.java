package ru.corvex.mailsmtp.outbound;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Доступ outbound-воркера к durable spool исходящих писем в mail-storage-service. */
@Component
public class StorageRawClient {

    private final String baseUrl;
    private final String internalToken;
    private final HttpClient http;

    public StorageRawClient(
            @Value("${corvex.storage.base-url:${CORVEX_STORAGE_BASE_URL:http://mail-storage-service:8085}}") String baseUrl,
            @Value("${corvex.security.internal-token:${CORVEX_INTERNAL_API_TOKEN:}}") String internalToken) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.internalToken = internalToken;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public static class RawNotFoundException extends IOException {
        public RawNotFoundException(String rawRef) {
            super("Raw message not found: " + rawRef);
        }
    }

    public byte[] download(String rawRef) throws IOException {
        HttpResponse<byte[]> response = send(request(rawRef).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) {
            throw new RawNotFoundException(rawRef);
        }
        if (response.statusCode() != 200) {
            throw new IOException("mail-storage вернул HTTP " + response.statusCode() + " для rawRef " + rawRef);
        }
        return response.body();
    }

    public void delete(String rawRef) {
        try {
            send(request(rawRef).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Осиротевший spool допустим; повторная внешняя отправка из-за ошибки удаления — хуже.
        }
    }

    private HttpRequest.Builder request(String rawRef) throws IOException {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IOException("CORVEX_INTERNAL_API_TOKEN не настроен");
        }
        return HttpRequest.newBuilder(URI.create(baseUrl + "/internal/raw/" + rawRef))
                .timeout(Duration.ofMinutes(1))
                .header("X-Corvex-Internal-Token", internalToken);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос в mail-storage прерван", e);
        }
    }
}
