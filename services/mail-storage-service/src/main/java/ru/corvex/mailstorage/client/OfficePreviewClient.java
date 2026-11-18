package ru.corvex.mailstorage.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.security.InternalTokenGuard;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Клиент внутреннего конвертера Office → PDF. Права и антивирус проверены вызывающим кодом;
 * сюда уходят только байты уже разрешённого вложения.
 */
@Component
public class OfficePreviewClient {

    private static final Logger log = LoggerFactory.getLogger(OfficePreviewClient.class);
    private static final String NAME_HEADER = "X-Office-File-Name";
    /** Тот же лимит, что у фронтенда и конвертера: больше не отправляем по сети. */
    private static final long MAX_SOURCE_BYTES = 25L * 1024 * 1024;

    private final boolean enabled;
    private final String baseUrl;
    private final Duration timeout;
    private final InternalTokenGuard tokens;
    private final HttpClient http;

    public OfficePreviewClient(
            @Value("${corvex.office-preview.enabled:${CORVEX_OFFICE_PREVIEW_ENABLED:false}}") boolean enabled,
            @Value("${corvex.office-preview.url:${CORVEX_OFFICE_PREVIEW_URL:http://office-preview-service:8092}}") String baseUrl,
            @Value("${corvex.office-preview.timeout:${CORVEX_OFFICE_PREVIEW_TIMEOUT:30s}}") String timeout,
            InternalTokenGuard tokens) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.timeout = DurationStyle.detectAndParse(timeout);
        this.tokens = tokens;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean enabled() {
        return enabled;
    }

    public InputStream convert(InputStream source, long sizeBytes, String fileName) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Предпросмотр Office выключен");
        }
        if (sizeBytes > MAX_SOURCE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Файл слишком большой");
        }
        String token;
        try {
            token = tokens.tokenOrFail();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }

        String safeName = fileName == null || fileName.isBlank() ? "document" : fileName;
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/office-preview/convert"))
                .timeout(timeout)
                .header(InternalTokenGuard.HEADER, token)
                .header(NAME_HEADER, Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(safeName.getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> source))
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 200) {
                return response.body();
            }
            try (InputStream ignored = response.body()) {
                // Имя файла не пишем даже на WARN: в нём бывают ФИО и номера договоров.
                log.warn("Конвертер Office ответил {} для вложения объёмом {} байт",
                        response.statusCode(), sizeBytes);
            }
            throw mapped(response.statusCode());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Конвертер предпросмотра недоступен", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Запрос в конвертер прерван", e);
        }
    }

    private ResponseStatusException mapped(int status) {
        if (status == 413) {
            return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Файл слишком большой");
        }
        if (status == 415 || status == 422) {
            return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Не удалось преобразовать документ");
        }
        if (status == 429 || status == 503 || status == 504) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Конвертер предпросмотра занят");
        }
        if (status == 501) {
            return new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Предпросмотр Office выключен");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Конвертер предпросмотра вернул HTTP " + status);
    }
}
