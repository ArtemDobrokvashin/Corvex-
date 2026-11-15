package ru.corvex.mailstorage.maildomain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Читает публичный ключ подписи из mail-smtp-service по внутреннему токену.
 * Любая ошибка, таймаут или неожиданный ответ означают «ключ не подтверждён»:
 * проверка DKIM тогда не может утверждать соответствие и обязана это сказать.
 */
public class HttpDkimKeyProbe implements DkimKeyProbe {

    private static final Logger log = LoggerFactory.getLogger(HttpDkimKeyProbe.class);

    private final URI endpoint;
    private final String internalToken;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public HttpDkimKeyProbe(URI endpoint, String internalToken, ObjectMapper mapper) {
        this.endpoint = endpoint;
        this.internalToken = internalToken;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Optional<SigningKey> signingKey(String domain) {
        if (internalToken == null || internalToken.isBlank()) {
            return Optional.empty();
        }
        try {
            String url = endpoint + (endpoint.getQuery() == null ? "?" : "&")
                    + "domain=" + URLEncoder.encode(domain, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("X-Corvex-Internal-Token", internalToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode body = mapper.readTree(response.body());
            String keyDomain = body.path("domain").asText("");
            String selector = body.path("selector").asText("");
            String key = body.path("publicKeyBase64").asText("");
            if (keyDomain.isBlank() || selector.isBlank() || key.isBlank()) {
                return Optional.empty();
            }
            // Домен и селектор попадают в DNS-имя: принимаем только безопасные.
            String normalizedDomain = MailDomainName.normalize(keyDomain);
            if (!selector.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) {
                return Optional.empty();
            }
            if (!key.matches("[A-Za-z0-9+/=]{32,4096}")) {
                return Optional.empty();
            }
            return Optional.of(new SigningKey(normalizedDomain.toLowerCase(Locale.ROOT), selector, key));
        } catch (Exception e) {
            log.warn("DKIM public key is unavailable from {}: {}", endpoint.getHost(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
