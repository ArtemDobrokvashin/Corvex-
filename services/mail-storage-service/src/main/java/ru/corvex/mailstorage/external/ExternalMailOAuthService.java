package ru.corvex.mailstorage.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.ExternalMailOAuthDtos;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.ExternalMailOAuthConnection;
import ru.corvex.mailstorage.domain.ExternalMailOAuthState;
import ru.corvex.mailstorage.repository.ExternalMailOAuthConnectionRepository;
import ru.corvex.mailstorage.repository.ExternalMailOAuthStateRepository;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Вход через Google и Microsoft 365: выдача адреса согласия и приём callback.
 *
 * <p>Callback приходит из браузера без JWT — доверять можно только одноразовому {@code state},
 * который мы сами выдали и сохранили в базе хэшем. Он привязан к владельцу ящика, провайдеру
 * и адресу возврата, живёт минуты и гасится при первом использовании.
 *
 * <p>Открытых редиректов нет: адрес возврата проверяется как локальный путь и собирается
 * только с нашим публичным адресом.
 */
@Service
public class ExternalMailOAuthService {

    private static final Logger log = LoggerFactory.getLogger(ExternalMailOAuthService.class);
    private static final Pattern RETURN_PATH = Pattern.compile("^/[A-Za-z0-9._~/-]*$");
    private static final String DEFAULT_RETURN_PATH = "/mail";
    private static final int STATE_BYTES = 32;
    private static final int VERIFIER_BYTES = 48;

    private final ExternalMailOAuthProperties props;
    private final ExternalMailOAuthClient client;
    private final ExternalMailSecretCipher cipher;
    private final ExternalMailOAuthStateRepository states;
    private final ExternalMailOAuthConnectionRepository connections;
    private final AuthUserClient auth;
    private final SecureRandom random = new SecureRandom();

    public ExternalMailOAuthService(ExternalMailOAuthProperties props,
                                    ExternalMailOAuthClient client,
                                    ExternalMailSecretCipher cipher,
                                    ExternalMailOAuthStateRepository states,
                                    ExternalMailOAuthConnectionRepository connections,
                                    AuthUserClient auth) {
        this.props = props;
        this.client = client;
        this.cipher = cipher;
        this.states = states;
        this.connections = connections;
        this.auth = auth;
    }

    public List<ExternalMailOAuthDtos.ProviderView> providers() {
        return java.util.Arrays.stream(ExternalMailOAuthProvider.values())
                .map(provider -> new ExternalMailOAuthDtos.ProviderView(provider.name(), provider.title(),
                        props.enabled(provider) && props.redirectConfigured(),
                        props.enabled(provider) && props.redirectConfigured()
                                ? provider.hint()
                                : "Не настроено администратором: нужны client id, secret и публичный адрес"))
                .toList();
    }

    @Transactional
    public ExternalMailOAuthDtos.AuthorizeResponse start(UUID actorId, Set<String> authorities,
                                                         ExternalMailOAuthDtos.AuthorizeRequest request) {
        ExternalMailOAuthProvider provider = ExternalMailOAuthProvider.parse(request == null ? null : request.provider());
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный провайдер входа");
        }
        requireEnabled(provider);
        UUID ownerId = request.targetUserId() == null ? actorId : request.targetUserId();
        requireManage(actorId, ownerId, authorities);
        AuthUserClient.MailboxOwner owner = auth.findById(ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Пользователь-владелец Corvex не найден"));
        if (!owner.active()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Пользователь-владелец Corvex неактивен");
        }

        Instant now = Instant.now();
        String state = randomToken(STATE_BYTES);
        String verifier = randomToken(VERIFIER_BYTES);
        ExternalMailOAuthState row = new ExternalMailOAuthState();
        row.setStateHash(sha256Hex(state));
        row.setOwnerId(ownerId);
        row.setActorId(actorId);
        row.setProvider(provider.name());
        row.setCodeVerifierEnc(cipher.encrypt(verifier));
        row.setReturnPath(returnPath(request.returnPath()));
        row.setCreatedAt(now);
        row.setExpiresAt(now.plusSeconds(props.stateTtlMinutes() * 60L));
        states.saveAndFlush(row);
        cleanUp(now);

        String url = client.authorizationUrl(provider, state, ExternalMailOAuthClient.codeChallenge(verifier));
        return new ExternalMailOAuthDtos.AuthorizeResponse(url, state, row.getExpiresAt());
    }

    /**
     * Обработка возврата из Google/Microsoft. Всегда возвращает адрес приложения: сбой
     * показывается пользователю понятной причиной, а не белым экраном провайдера.
     */
    @Transactional
    public String callback(String code, String state, String providerError) {
        ExternalMailOAuthState row = state == null ? null : states.findForUpdate(sha256Hex(state)).orElse(null);
        if (row == null) {
            return errorRedirect(DEFAULT_RETURN_PATH, "invalid_state");
        }
        String returnPath = safeReturnPath(row.getReturnPath());
        if (row.getUsedAt() != null || row.getExpiresAt().isBefore(Instant.now())) {
            return errorRedirect(returnPath, "state_expired");
        }
        row.setUsedAt(Instant.now());
        states.saveAndFlush(row);

        if (providerError != null && !providerError.isBlank()) {
            log.info("Вход через {} отклонён пользователем или провайдером: {}", row.getProvider(), providerError);
            return errorRedirect(returnPath, "access_denied");
        }
        if (code == null || code.isBlank()) {
            return errorRedirect(returnPath, "no_code");
        }
        ExternalMailOAuthProvider provider = ExternalMailOAuthProvider.parse(row.getProvider());
        if (provider == null) {
            return errorRedirect(returnPath, "invalid_state");
        }
        try {
            String verifier = cipher.decrypt(row.getCodeVerifierEnc());
            ExternalMailOAuthClient.Tokens tokens = client.exchange(provider, code, verifier);
            String email = client.identityEmail(provider, tokens);
            ExternalMailOAuthConnection connection = new ExternalMailOAuthConnection();
            connection.setOwnerId(row.getOwnerId());
            connection.setProvider(provider.name());
            connection.setEmail(email);
            connection.setAccessTokenEnc(cipher.encrypt(tokens.accessToken()));
            connection.setRefreshTokenEnc(tokens.refreshToken() == null ? null : cipher.encrypt(tokens.refreshToken()));
            connection.setExpiresAt(tokens.expiresAt());
            connection.setScope(tokens.scope());
            connection.setCreatedAt(Instant.now());
            connections.saveAndFlush(connection);
            log.info("Согласие {} получено для ящика {} (владелец {})", provider, email, row.getOwnerId());
            return redirect(returnPath, "oauth=connected&connection=" + connection.getId()
                    + "&provider=" + provider.name() + "&email=" + urlEncode(email));
        } catch (ExternalMailOAuthClient.OAuthCallException e) {
            log.warn("Обмен кода {} не удался: {}", provider, e.getMessage());
            return errorRedirect(returnPath, e.kind() == ExternalMailOAuthClient.OAuthCallException.Kind.REJECTED
                    ? "access_denied" : "exchange_failed");
        } catch (RuntimeException e) {
            log.warn("Не удалось сохранить согласие {}: {}", provider, e.getClass().getSimpleName());
            return errorRedirect(returnPath, "exchange_failed");
        }
    }

    /**
     * Забрать токены из незавершённого подключения при создании внешнего ящика.
     * Возвращает уже зашифрованные значения: сервис ящиков не работает с открытыми токенами.
     */
    @Transactional
    public Claimed claim(UUID ownerId, UUID connectionId, ExternalMailOAuthProvider provider, String email) {
        if (connectionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Для входа через " + (provider == null ? "провайдера" : provider.title())
                            + " сначала подтвердите согласие");
        }
        ExternalMailOAuthConnection connection = connections.findOpenForUpdate(connectionId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Подключение не найдено или уже использовано; войдите заново"));
        if (connection.getCreatedAt().plusSeconds(props.connectionTtlMinutes() * 60L).isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Срок подключения истёк; войдите заново");
        }
        ExternalMailOAuthProvider actual = ExternalMailOAuthProvider.parse(connection.getProvider());
        if (provider == null || actual != provider) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Подключение выдано другому провайдеру; войдите заново");
        }
        if (email == null || !email.equalsIgnoreCase(connection.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Адрес ящика не совпадает с тем, под которым вы вошли; изменять адрес нельзя");
        }
        connection.setConsumedAt(Instant.now());
        connections.saveAndFlush(connection);
        return new Claimed(connection.getProvider(), connection.getEmail(), connection.getAccessTokenEnc(),
                connection.getRefreshTokenEnc(), connection.getExpiresAt(), connection.getScope());
    }

    @Transactional(readOnly = true)
    public ExternalMailOAuthDtos.ConnectionView connection(UUID actorId, Set<String> authorities,
                                                           UUID connectionId) {
        ExternalMailOAuthConnection connection = connections.findById(connectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Подключение не найдено"));
        requireManage(actorId, connection.getOwnerId(), authorities);
        return new ExternalMailOAuthDtos.ConnectionView(connection.getId(), connection.getProvider(),
                connection.getEmail(), connection.getExpiresAt());
    }

    /** Токены подключения в зашифрованном виде: расшифровывает только сервис токенов. */
    public record Claimed(String provider, String email, String accessTokenEnc, String refreshTokenEnc,
                          Instant expiresAt, String scope) {
    }

    private void cleanUp(Instant now) {
        try {
            states.deleteExpired(now.minusSeconds(3600));
            connections.deleteConsumedBefore(now.minusSeconds(3600));
        } catch (RuntimeException e) {
            // Уборка не должна мешать входу.
            log.debug("Уборка просроченных OAuth-записей не удалась: {}", e.getClass().getSimpleName());
        }
    }

    private void requireEnabled(ExternalMailOAuthProvider provider) {
        if (!props.enabled(provider)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Вход через " + provider.title() + " не настроен: задайте CORVEX_OAUTH_"
                            + provider.name() + "_CLIENT_ID и _CLIENT_SECRET");
        }
        if (!props.redirectConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Вход через " + provider.title() + " не настроен: задайте CORVEX_PUBLIC_BASE_URL");
        }
        cipher.requireConfigured();
    }

    private void requireManage(UUID actorId, UUID ownerId, Set<String> authorities) {
        boolean admin = authorities != null && (authorities.contains(ExternalMailAccountService.ADMIN_PERMISSION)
                || authorities.contains("*"));
        boolean self = actorId != null && actorId.equals(ownerId) && authorities != null
                && (authorities.contains(ExternalMailAccountService.PERMISSION) || authorities.contains("*"));
        if (!admin && !self) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Нет права подключать внешние ящики (" + ExternalMailAccountService.PERMISSION + ")");
        }
    }

    private static String returnPath(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_RETURN_PATH;
        }
        String value = requested.trim();
        if (value.length() > 200 || !RETURN_PATH.matcher(value).matches() || value.startsWith("//")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный адрес возврата");
        }
        return value;
    }

    private static String safeReturnPath(String stored) {
        return stored != null && RETURN_PATH.matcher(stored).matches() && !stored.startsWith("//")
                ? stored : DEFAULT_RETURN_PATH;
    }

    private String redirect(String returnPath, String query) {
        String base = props.publicBaseUrl();
        String path = base == null ? returnPath : base + returnPath;
        String separator = path.contains("?") ? "&" : "?";
        return path + separator + query;
    }

    private String errorRedirect(String returnPath, String reason) {
        return redirect(returnPath, "oauth=error&reason=" + urlEncode(reason));
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
