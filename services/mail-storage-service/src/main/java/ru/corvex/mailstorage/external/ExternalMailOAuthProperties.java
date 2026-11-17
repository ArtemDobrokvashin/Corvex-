package ru.corvex.mailstorage.external;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Настройки OAuth. Приложение регистрируется администратором в Google Cloud и Azure;
 * client secret хранится только в переменных окружения, а не в базе.
 *
 * <p>Redirect URI обязан совпадать с зарегистрированным до символа, поэтому он собирается
 * из {@code CORVEX_PUBLIC_BASE_URL}. Без домена провайдеры принимают loopback-адрес
 * вида {@code http://localhost:8080}: этого достаточно для приёмки на ноутбуке.
 */
@Component
public class ExternalMailOAuthProperties {

    private final String googleClientId;
    private final String googleClientSecret;
    private final String microsoftClientId;
    private final String microsoftClientSecret;
    private final String microsoftTenant;
    private final String publicBaseUrl;
    private final int timeoutSeconds;
    private final int stateTtlMinutes;
    private final int connectionTtlMinutes;

    public ExternalMailOAuthProperties(
            @Value("${corvex.oauth.google-client-id:${CORVEX_OAUTH_GOOGLE_CLIENT_ID:}}") String googleClientId,
            @Value("${corvex.oauth.google-client-secret:${CORVEX_OAUTH_GOOGLE_CLIENT_SECRET:}}") String googleClientSecret,
            @Value("${corvex.oauth.microsoft-client-id:${CORVEX_OAUTH_MICROSOFT_CLIENT_ID:}}") String microsoftClientId,
            @Value("${corvex.oauth.microsoft-client-secret:${CORVEX_OAUTH_MICROSOFT_CLIENT_SECRET:}}") String microsoftClientSecret,
            @Value("${corvex.oauth.microsoft-tenant:${CORVEX_OAUTH_MICROSOFT_TENANT:common}}") String microsoftTenant,
            @Value("${corvex.oauth.public-base-url:${CORVEX_PUBLIC_BASE_URL:}}") String publicBaseUrl,
            @Value("${corvex.oauth.timeout-seconds:${CORVEX_OAUTH_TIMEOUT_SECONDS:15}}") int timeoutSeconds,
            @Value("${corvex.oauth.state-ttl-minutes:${CORVEX_OAUTH_STATE_TTL_MINUTES:10}}") int stateTtlMinutes,
            @Value("${corvex.oauth.connection-ttl-minutes:${CORVEX_OAUTH_CONNECTION_TTL_MINUTES:30}}") int connectionTtlMinutes) {
        this.googleClientId = clean(googleClientId);
        this.googleClientSecret = clean(googleClientSecret);
        this.microsoftClientId = clean(microsoftClientId);
        this.microsoftClientSecret = clean(microsoftClientSecret);
        String tenant = clean(microsoftTenant);
        this.microsoftTenant = tenant == null ? "common" : tenant.replaceAll("[^A-Za-z0-9.-]", "");
        this.publicBaseUrl = stripTrailingSlash(clean(publicBaseUrl));
        this.timeoutSeconds = Math.max(5, Math.min(timeoutSeconds, 60));
        this.stateTtlMinutes = Math.max(1, Math.min(stateTtlMinutes, 30));
        this.connectionTtlMinutes = Math.max(5, Math.min(connectionTtlMinutes, 120));
    }

    public String clientId(ExternalMailOAuthProvider provider) {
        return provider == ExternalMailOAuthProvider.GOOGLE ? googleClientId : microsoftClientId;
    }

    public String clientSecret(ExternalMailOAuthProvider provider) {
        return provider == ExternalMailOAuthProvider.GOOGLE ? googleClientSecret : microsoftClientSecret;
    }

    public boolean enabled(ExternalMailOAuthProvider provider) {
        return provider != null && clientId(provider) != null && clientSecret(provider) != null;
    }

    /** Без публичного адреса провайдер не примет redirect_uri — предлагать вход бессмысленно. */
    public boolean redirectConfigured() {
        return publicBaseUrl != null;
    }

    public String publicBaseUrl() {
        return publicBaseUrl;
    }

    public String redirectUri() {
        if (publicBaseUrl == null) {
            return null;
        }
        return publicBaseUrl + "/api/v1/mail/external-accounts/oauth/callback";
    }

    public String tenant() {
        return microsoftTenant;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public int stateTtlMinutes() {
        return stateTtlMinutes;
    }

    public int connectionTtlMinutes() {
        return connectionTtlMinutes;
    }

    public String authorizeEndpoint(ExternalMailOAuthProvider provider) {
        return provider.authorizeEndpoint().replace("{tenant}", microsoftTenant);
    }

    public String tokenEndpoint(ExternalMailOAuthProvider provider) {
        return provider.tokenEndpoint().replace("{tenant}", microsoftTenant);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
