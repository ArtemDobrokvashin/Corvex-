package ru.corvex.mailstorage.external;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.domain.ExternalMailAccount;
import ru.corvex.mailstorage.domain.ExternalMailOAuthToken;
import ru.corvex.mailstorage.repository.ExternalMailOAuthTokenRepository;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Обновление токенов без сети. Главное здесь — различать «провайдер недоступен» и «согласие
 * отозвано»: в первом случае сбор повторится, во втором пользователь должен войти заново.
 */
class ExternalMailOAuthTokenServiceTest {

    private ExternalMailOAuthTokenRepository tokens;
    private ExternalMailOAuthClient client;
    private ExternalMailSecretCipher cipher;
    private ExternalMailOAuthTokenService service;
    private ExternalMailAccount account;

    @BeforeEach
    void setUp() {
        tokens = mock(ExternalMailOAuthTokenRepository.class);
        client = mock(ExternalMailOAuthClient.class);
        cipher = new ExternalMailSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        service = new ExternalMailOAuthTokenService(tokens, client, cipher,
                new ExternalMailReauthMarker(tokens));
        account = new ExternalMailAccount();
        account.setId(UUID.randomUUID());
        account.setAuthType(ExternalMailAccount.AuthType.OAUTH2);
        account.setProvider(ExternalMailAccount.Provider.GMAIL);
    }

    private ExternalMailOAuthToken row(Instant expiresAt, String refreshToken) {
        ExternalMailOAuthToken row = new ExternalMailOAuthToken();
        row.setAccountId(account.getId());
        row.setProvider("GOOGLE");
        row.setEmail("ivan@company.ru");
        row.setAccessTokenEnc(cipher.encrypt("old-access"));
        row.setRefreshTokenEnc(refreshToken == null ? null : cipher.encrypt(refreshToken));
        row.setExpiresAt(expiresAt);
        row.setCreatedAt(Instant.now().minusSeconds(3600));
        row.setUpdatedAt(Instant.now().minusSeconds(3600));
        return row;
    }

    @Test
    void freshTokenIsUsedWithoutCallingProvider() throws Exception {
        when(tokens.findById(account.getId())).thenReturn(Optional.of(row(Instant.now().plusSeconds(3600), "rt")));

        assertEquals("old-access", service.accessToken(account));
        verify(client, never()).refresh(any(), any());
    }

    @Test
    void expiringTokenIsRefreshedAndRotationIsSaved() throws Exception {
        ExternalMailOAuthToken row = row(Instant.now().plusSeconds(10), "rt-old");
        when(tokens.findById(account.getId())).thenReturn(Optional.of(row));
        when(client.refresh(ExternalMailOAuthProvider.GOOGLE, "rt-old"))
                .thenReturn(new ExternalMailOAuthClient.Tokens("new-access", "rt-new",
                        Instant.now().plusSeconds(3600), "scope", null));

        assertEquals("new-access", service.accessToken(account));
        assertEquals("rt-new", cipher.decrypt(row.getRefreshTokenEnc()), "ротированный refresh token сохраняется");
        assertEquals("new-access", cipher.decrypt(row.getAccessTokenEnc()));
        assertFalse(row.isReauthRequired());
        verify(tokens).saveAndFlush(row);
    }

    @Test
    void revokedConsentMarksReauthAndStopsWith409() throws Exception {
        ExternalMailOAuthToken row = row(Instant.now().plusSeconds(10), "rt-old");
        when(tokens.findById(account.getId())).thenReturn(Optional.of(row));
        when(client.refresh(any(), any())).thenThrow(new ExternalMailOAuthClient.OAuthCallException(
                ExternalMailOAuthClient.OAuthCallException.Kind.REJECTED, "отозвано"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.accessToken(account));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(row.isReauthRequired());
        assertTrue(service.reauthRequired(account.getId()));
    }

    @Test
    void providerOutageKeepsReauthFlagClear() throws Exception {
        ExternalMailOAuthToken row = row(Instant.now().plusSeconds(10), "rt-old");
        when(tokens.findById(account.getId())).thenReturn(Optional.of(row));
        when(client.refresh(any(), any())).thenThrow(new ExternalMailOAuthClient.OAuthCallException(
                ExternalMailOAuthClient.OAuthCallException.Kind.TEMPORARY, "сеть"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.accessToken(account));

        assertEquals(503, error.getStatusCode().value(), "временный сбой не требует повторного входа");
        assertFalse(row.isReauthRequired());
    }

    @Test
    void alreadyRevokedAccountFailsWithoutProviderCall() throws Exception {
        ExternalMailOAuthToken row = row(Instant.now().plusSeconds(3600), "rt");
        row.setReauthRequired(true);
        when(tokens.findById(account.getId())).thenReturn(Optional.of(row));

        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.accessToken(account)).getStatusCode().value());
        verify(client, never()).refresh(any(), any());
    }

    @Test
    void missingTokenRowMeansIncompleteConnection() {
        when(tokens.findById(account.getId())).thenReturn(Optional.empty());

        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.accessToken(account)).getStatusCode().value());
    }

    @Test
    void rowWithoutRefreshTokenRequiresNewConsent() {
        ExternalMailOAuthToken row = row(Instant.now().plusSeconds(10), null);
        when(tokens.findById(account.getId())).thenReturn(Optional.of(row));

        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.accessToken(account)).getStatusCode().value());
        assertTrue(row.isReauthRequired());
    }
}
