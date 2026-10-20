package ru.corvex.mailspam.pipeline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Читает сырой .eml из durable spool mail-storage-service по internal API. */
@Component
public class RawMessageClient {

    private final String baseUrl;
    private final String internalToken;
    private final HttpClient http;

    public RawMessageClient(
            @Value("${corvex.storage.base-url:${CORVEX_STORAGE_BASE_URL:http://mail-storage-service:8085}}") String baseUrl,
            @Value("${corvex.security.internal-token:${CORVEX_INTERNAL_API_TOKEN:}}") String internalToken) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.internalToken = internalToken;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public byte[] download(String rawRef) throws IOException {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IOException("CORVEX_INTERNAL_API_TOKEN не настроен");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/raw/" + rawRef))
                .timeout(Duration.ofMinutes(1))
                .header("X-Corvex-Internal-Token", internalToken)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("mail-storage вернул HTTP " + response.statusCode() + " для rawRef " + rawRef);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Чтение spool прервано", e);
        }
    }
}
