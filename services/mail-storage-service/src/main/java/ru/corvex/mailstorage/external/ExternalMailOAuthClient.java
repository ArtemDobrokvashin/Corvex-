package ru.corvex.mailstorage.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Обмен кода авторизации и обновление токенов Google/Microsoft.
 *
 * <p>Здесь нет своей реализации OAuth: только два запроса по спецификации (authorization code
 * и refresh token) и разбор ответа. Пароли и токены не попадают ни в сообщения об ошибках,
 * ни в журнал: наружу отдаётся категория сбоя и понятное действие.
 *
 * <p>{@code id_token} читается без проверки подписи: он получен по TLS непосредственно от
 * token endpoint, а не пришёл из браузера (RFC 8252, §8.4). При этом проверяются {@code aud},
 * {@code iss} и {@code exp} — этого достаточно, чтобы не принять чужой токен.
 */
@Component
public class ExternalMailOAuthClient {

    /** Токены провайдера. Поле {@code refreshToken} есть только при первом согласии. */
    public record Tokens(String accessToken, String refreshToken, Instant expiresAt, String scope,
                         String idToken) {
    }

    record Endpoints(String authorize, String token) {
    }

    /** Категория сбоя решает, повторять ли операцию и что показать пользователю. */
    public static final class OAuthCallException extends Exception {

        public enum Kind {REJECTED, TEMPORARY, MISCONFIGURED}

        private final Kind kind;

        OAuthCallException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    private final ExternalMailOAuthProperties props;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Map<ExternalMailOAuthProvider, Endpoints> endpoints =
            new EnumMap<>(ExternalMailOAuthProvider.class);

    @Autowired
    public ExternalMailOAuthClient(ExternalMailOAuthProperties props, ObjectMapper json) {
        this(props, json, Map.of());
    }

    /** Тестовый конструктор: адреса подменяются на loopback-заглушку. */
    ExternalMailOAuthClient(ExternalMailOAuthProperties props, ObjectMapper json,
                            Map<ExternalMailOAuthProvider, Endpoints> override) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        for (ExternalMailOAuthProvider provider : ExternalMailOAuthProvider.values()) {
            Endpoints custom = override == null ? null : override.get(provider);
            endpoints.put(provider, custom == null
                    ? new Endpoints(props.authorizeEndpoint(provider), props.tokenEndpoint(provider))
                    : custom);
        }
    }

    String authorizationUrl(ExternalMailOAuthProvider provider, String state, String codeChallenge) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", props.clientId(provider));
        params.put("response_type", "code");
        params.put("redirect_uri", props.redirectUri());
        params.put("scope", provider.scope());
        params.put("state", state);
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        if (provider == ExternalMailOAuthProvider.GOOGLE) {
            // Без access_type=offline Google не выдаёт refresh token, и сбор остановится
            // через час; prompt=consent заставляет выдать его и при повторном входе.
            params.put("access_type", "offline");
            params.put("prompt", "consent");
        } else {
            params.put("response_mode", "query");
        }
        return endpoints.get(provider).authorize() + "?" + form(params);
    }

    Tokens exchange(ExternalMailOAuthProvider provider, String code, String codeVerifier)
            throws OAuthCallException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("redirect_uri", props.redirectUri());
        body.put("client_id", props.clientId(provider));
        body.put("client_secret", props.clientSecret(provider));
        body.put("code_verifier", codeVerifier);
        return tokenRequest(provider, body);
    }

    Tokens refresh(ExternalMailOAuthProvider provider, String refreshToken) throws OAuthCallException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        body.put("client_id", props.clientId(provider));
        body.put("client_secret", props.clientSecret(provider));
        return tokenRequest(provider, body);
    }

    /**
     * Адрес ящика из id_token. Нужен, чтобы связать согласие с конкретным ящиком: пользователь
     * мог войти в другой аккаунт, и молча подключить его к чужому профилю нельзя.
     */
    String identityEmail(ExternalMailOAuthProvider provider, Tokens tokens) throws OAuthCallException {
        if (tokens == null || tokens.idToken() == null) {
            throw new OAuthCallException(OAuthCallException.Kind.MISCONFIGURED,
                    provider.title() + " не вернул id_token: проверьте области openid и email");
        }
        JsonNode claims;
        try {
            claims = json.readTree(decodePayload(tokens.idToken()));
        } catch (IOException | IllegalArgumentException e) {
            throw new OAuthCallException(OAuthCallException.Kind.MISCONFIGURED,
                    "Не удалось прочитать ответ " + provider.title());
        }
        String audience = claims.path("aud").asText("");
        String expectedAudience = props.clientId(provider);
        if (expectedAudience != null && !expectedAudience.equals(audience)) {
            throw new OAuthCallException(OAuthCallException.Kind.MISCONFIGURED,
                    "Ответ " + provider.title() + " выдан другому приложению");
        }
        long expires = claims.path("exp").asLong(0);
        if (expires > 0 && Instant.ofEpochSecond(expires).isBefore(Instant.now().minusSeconds(60))) {
            throw new OAuthCallException(OAuthCallException.Kind.TEMPORARY,
                    "Ответ " + provider.title() + " просрочен, повторите вход");
        }
        String issuer = claims.path("iss").asText("");
        if (!issuerAllowed(provider, issuer)) {
            throw new OAuthCallException(OAuthCallException.Kind.MISCONFIGURED,
                    "Ответ пришёл от неизвестного издателя " + provider.title());
        }
        for (String field : new String[]{"email", "preferred_username", "upn"}) {
            String value = claims.path(field).asText("");
            if (value.contains("@") && !value.contains(" ") && value.length() <= 320) {
                return value.toLowerCase(java.util.Locale.ROOT);
            }
        }
        throw new OAuthCallException(OAuthCallException.Kind.MISCONFIGURED,
                provider.title() + " не сообщил адрес ящика: проверьте области openid и email");
    }

    private Tokens tokenRequest(ExternalMailOAuthProvider provider, Map<String, String> body)
            throws OAuthCallException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.get(provider).token()))
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthCallException(OAuthCallException.Kind.TEMPORARY,
                    "Запрос к " + provider.title() + " прерван");
        } catch (IOException e) {
            throw new OAuthCallException(OAuthCallException.Kind.TEMPORARY,
                    "Не удалось связаться с " + provider.title() + ": " + e.getClass().getSimpleName());
        }
        JsonNode payload = parse(response.body());
        if (response.statusCode() / 100 != 2) {
            throw failure(provider, response.statusCode(), payload);
        }
        String accessToken = payload.path("access_token").asText("");
        if (accessToken.isBlank()) {
            throw new OAuthCallException(OAuthCallException.Kind.MISCONFIGURED,
                    provider.title() + " не выдал токен доступа");
        }
        long expiresIn = payload.path("expires_in").asLong(3600);
        if (expiresIn <= 0) {
            expiresIn = 3600;
        }
        String refreshToken = payload.path("refresh_token").asText(null);
        String scope = payload.path("scope").asText(null);
        String idToken = payload.path("id_token").asText(null);
        return new Tokens(accessToken, refreshToken, Instant.now().plusSeconds(expiresIn - 30), scope, idToken);
    }

    private OAuthCallException failure(ExternalMailOAuthProvider provider, int status, JsonNode payload) {
        String error = payload.path("error").asText("");
        OAuthCallException.Kind kind = switch (error) {
            case "invalid_grant", "access_denied" -> OAuthCallException.Kind.REJECTED;
            case "invalid_client", "unauthorized_client", "invalid_request", "unsupported_grant_type" ->
                    OAuthCallException.Kind.MISCONFIGURED;
            // Всё остальное считаем временным: 5xx и незнакомые коды лечатся повтором,
            // а не повторным согласием.
            default -> OAuthCallException.Kind.TEMPORARY;
        };
        String message = switch (kind) {
            case REJECTED -> "Доступ к ящику отклонён или согласие отозвано: войдите в "
                    + provider.title() + " заново";
            case MISCONFIGURED -> "Приложение " + provider.title()
                    + " настроено неверно: проверьте client id, secret и redirect URI";
            case TEMPORARY -> provider.title() + " временно недоступен, повторите вход позже";
        };
        return new OAuthCallException(kind, message + " (код " + status + ")");
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }

    private static boolean issuerAllowed(ExternalMailOAuthProvider provider, String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return false;
        }
        return provider == ExternalMailOAuthProvider.GOOGLE
                ? issuer.equals("https://accounts.google.com") || issuer.equals("accounts.google.com")
                : issuer.startsWith("https://login.microsoftonline.com/")
                        || issuer.startsWith("https://sts.windows.net/");
    }

    static String decodePayload(String idToken) {
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("id_token без полезной нагрузки");
        }
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }

    /** PKCE S256: base64url(sha256(verifier)) без выравнивания. */
    static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                        StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
