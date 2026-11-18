package ru.corvex.mailav.pipeline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Открывает сырой .eml из durable spool mail-storage-service потоком для ClamAV INSTREAM. */
@Component
public class RawMessageClient {

    /** Одна попытка + одно повторение: этого хватает на штатный рестарт бэкенда. */
    private static final int TRANSPORT_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = Long.getLong("corvex.av.raw-retry-delay-ms", 500L);

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

    public InputStream open(String rawRef) throws IOException {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IOException("CORVEX_INTERNAL_API_TOKEN не настроен");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/raw/" + rawRef))
                .timeout(Duration.ofMinutes(2))
                .header("X-Corvex-Internal-Token", internalToken)
                .GET()
                .build();
        // GET идемпотентен: одно повторение переживает перезапуск mail-storage-service.
        // Иначе HttpClient переиспользует keep-alive соединение, уже закрытое бэкендом,
        // и первый же вызов падает с ClosedChannelException.
        IOException lastTransportFailure = null;
        for (int attempt = 1; attempt <= TRANSPORT_ATTEMPTS; attempt++) {
            HttpResponse<InputStream> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Чтение spool прервано", e);
            } catch (IOException e) {
                lastTransportFailure = e;
                if (attempt < TRANSPORT_ATTEMPTS) pauseBeforeRetry();
                continue;
            }
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("mail-storage вернул HTTP " + response.statusCode() + " для rawRef " + rawRef);
            }
            return response.body();
        }
        if (lastTransportFailure == null) {
            throw new IOException("mail-storage не ответил на чтение spool: " + rawRef);
        }
        throw new AvStorageUnavailableException(rawRef, lastTransportFailure);
    }

    private static void pauseBeforeRetry() throws IOException {
        if (RETRY_DELAY_MS <= 0) return;
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Ожидание повтора чтения spool прервано", e);
        }
    }
}
