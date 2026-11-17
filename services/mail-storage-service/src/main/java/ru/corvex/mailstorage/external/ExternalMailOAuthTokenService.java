package ru.corvex.mailstorage.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.domain.ExternalMailAccount;
import ru.corvex.mailstorage.domain.ExternalMailOAuthToken;
import ru.corvex.mailstorage.repository.ExternalMailOAuthTokenRepository;

import java.time.Instant;
import java.util.UUID;

/**
 * Токены доступа подключённых OAuth-ящиков: выдача актуального токена и обновление.
 *
 * <p>Токен доступа живёт около часа, а сбор работает сутками, поэтому перед каждым
 * подключением он обновляется по refresh token. Обновление синхронизировано в пределах
 * процесса: параллельные сборы одного ящика не должны одновременно гасить один refresh token
 * (провайдеры считают повторное использование подозрительным и могут отозвать согласие).
 *
 * <p>Отказ {@code invalid_grant} — это не сбой сети, а отозванное согласие или смена пароля.
 * В этом случае вход помечается как требующий повторного согласия, а текст ошибки говорит,
 * что делать, вместо бесконечных 401 в журнале.
 */
@Service
public class ExternalMailOAuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(ExternalMailOAuthTokenService.class);
    private static final long REFRESH_MARGIN_SECONDS = 60;

    private final ExternalMailOAuthTokenRepository tokens;
    private final ExternalMailOAuthClient client;
    private final ExternalMailSecretCipher cipher;

    public ExternalMailOAuthTokenService(ExternalMailOAuthTokenRepository tokens,
                                         ExternalMailOAuthClient client,
                                         ExternalMailSecretCipher cipher) {
        this.tokens = tokens;
        this.client = client;
        this.cipher = cipher;
    }

    /** Сохранить токены после создания внешнего ящика (значения уже зашифрованы). */
    @Transactional
    public void store(UUID accountId, ExternalMailOAuthService.Claimed claimed) {
        ExternalMailOAuthToken row = tokens.findById(accountId).orElseGet(ExternalMailOAuthToken::new);
        Instant now = Instant.now();
        row.setAccountId(accountId);
        row.setProvider(claimed.provider());
        row.setEmail(claimed.email());
        row.setAccessTokenEnc(claimed.accessTokenEnc());
        row.setRefreshTokenEnc(claimed.refreshTokenEnc());
        row.setExpiresAt(claimed.expiresAt());
        row.setScope(claimed.scope());
        row.setReauthRequired(false);
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(now);
        }
        row.setUpdatedAt(now);
        tokens.saveAndFlush(row);
    }

    @Transactional
    public void delete(UUID accountId) {
        tokens.deleteById(accountId);
    }

    @Transactional(readOnly = true)
    public boolean connected(UUID accountId) {
        return tokens.findById(accountId).isPresent();
    }

    @Transactional(readOnly = true)
    public boolean reauthRequired(UUID accountId) {
        return tokens.findById(accountId).map(ExternalMailOAuthToken::isReauthRequired).orElse(false);
    }

    /** Токен доступа для IMAP/SMTP. Обновляет его, если до истечения меньше минуты. */
    @Transactional
    public synchronized String accessToken(ExternalMailAccount account) {
        UUID accountId = account.getId();
        ExternalMailOAuthToken row = tokens.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ящик подключён не полностью: войдите через провайдера заново"));
        if (row.isReauthRequired()) {
            throw reauthRequired();
        }
        if (row.getExpiresAt() != null
                && row.getExpiresAt().isAfter(Instant.now().plusSeconds(REFRESH_MARGIN_SECONDS))) {
            return cipher.decrypt(row.getAccessTokenEnc());
        }
        String refreshToken = row.getRefreshTokenEnc() == null ? null : cipher.decrypt(row.getRefreshTokenEnc());
        if (refreshToken == null) {
            markReauth(row);
            throw reauthRequired();
        }
        ExternalMailOAuthProvider provider = ExternalMailOAuthProvider.parse(row.getProvider());
        if (provider == null) {
            markReauth(row);
            throw reauthRequired();
        }
        try {
            ExternalMailOAuthClient.Tokens refreshed = client.refresh(provider, refreshToken);
            row.setAccessTokenEnc(cipher.encrypt(refreshed.accessToken()));
            if (refreshed.refreshToken() != null) {
                // Некоторые провайдеры ротируют refresh token: старый после обновления невалиден.
                row.setRefreshTokenEnc(cipher.encrypt(refreshed.refreshToken()));
            }
            row.setExpiresAt(refreshed.expiresAt());
            if (refreshed.scope() != null) {
                row.setScope(refreshed.scope());
            }
            row.setUpdatedAt(Instant.now());
            tokens.saveAndFlush(row);
            return refreshed.accessToken();
        } catch (ExternalMailOAuthClient.OAuthCallException e) {
            if (e.kind() == ExternalMailOAuthClient.OAuthCallException.Kind.REJECTED) {
                markReauth(row);
                throw reauthRequired();
            }
            log.warn("Не удалось обновить токен {}: {}", provider, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Провайдер временно недоступен, сбор повторится: " + e.getMessage());
        }
    }

    private void markReauth(ExternalMailOAuthToken row) {
        row.setReauthRequired(true);
        row.setUpdatedAt(Instant.now());
        tokens.saveAndFlush(row);
        log.warn("Ящик {} требует повторного согласия OAuth", row.getEmail());
    }

    private static ResponseStatusException reauthRequired() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Согласие Google/Microsoft отозвано или истекло: подключите ящик заново через кнопку входа");
    }
}
