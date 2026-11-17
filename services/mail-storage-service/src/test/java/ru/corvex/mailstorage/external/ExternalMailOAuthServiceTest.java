package ru.corvex.mailstorage.external;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.ExternalMailOAuthDtos;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.ExternalMailOAuthConnection;
import ru.corvex.mailstorage.domain.ExternalMailOAuthState;
import ru.corvex.mailstorage.repository.ExternalMailOAuthConnectionRepository;
import ru.corvex.mailstorage.repository.ExternalMailOAuthStateRepository;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Поток согласия без сети: проверяется то, что легко сломать незаметно — одноразовость state,
 * привязка к владельцу, запрет открытого редиректа и невозможность переиспользовать согласие.
 */
class ExternalMailOAuthServiceTest {

    private static final UUID ACTOR = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Set<String> PERMISSIONS = Set.of("MAIL_EXTERNAL_ACCOUNTS");

    private ExternalMailOAuthClient client;
    private ExternalMailSecretCipher cipher;
    private ExternalMailOAuthStateRepository states;
    private ExternalMailOAuthConnectionRepository connections;
    private ExternalMailOAuthService service;

    @BeforeEach
    void setUp() {
        client = mock(ExternalMailOAuthClient.class);
        cipher = new ExternalMailSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        states = mock(ExternalMailOAuthStateRepository.class);
        connections = mock(ExternalMailOAuthConnectionRepository.class);
        AuthUserClient auth = mock(AuthUserClient.class);
        when(auth.findById(OWNER)).thenReturn(Optional.of(
                new AuthUserClient.MailboxOwner(OWNER, "ivan@company.ru", "Иван", "ACTIVE", 1024)));
        when(connections.saveAndFlush(any(ExternalMailOAuthConnection.class))).thenAnswer(invocation -> {
            ExternalMailOAuthConnection row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
            }
            return row;
        });
        ExternalMailOAuthProperties props = new ExternalMailOAuthProperties("google-client", "google-secret",
                "ms-client", "ms-secret", "common", "https://mail.example.test", 5, 10, 30);
        service = new ExternalMailOAuthService(props, client, cipher, states, connections, auth);
    }

    private ExternalMailOAuthState state(String stateValue, String returnPath) {
        ExternalMailOAuthState row = new ExternalMailOAuthState();
        row.setStateHash(ExternalMailOAuthService.sha256Hex(stateValue));
        row.setOwnerId(OWNER);
        row.setActorId(ACTOR);
        row.setProvider("GOOGLE");
        row.setCodeVerifierEnc(cipher.encrypt("verifier-1"));
        row.setReturnPath(returnPath);
        row.setCreatedAt(Instant.now());
        row.setExpiresAt(Instant.now().plusSeconds(600));
        return row;
    }

    @Test
    void startStoresOnlyHashOfStateAndReturnsGoogleUrl() {
        when(client.authorizationUrl(eq(ExternalMailOAuthProvider.GOOGLE), any(), any()))
                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=x");

        ExternalMailOAuthDtos.AuthorizeResponse response = service.start(ACTOR, PERMISSIONS,
                new ExternalMailOAuthDtos.AuthorizeRequest("GOOGLE", null, "/mail"));

        assertNotNull(response.authorizationUrl());
        assertTrue(response.expiresAt().isAfter(Instant.now()));
        ArgumentCaptor<ExternalMailOAuthState> saved = ArgumentCaptor.forClass(ExternalMailOAuthState.class);
        org.mockito.Mockito.verify(states).saveAndFlush(saved.capture());
        assertNotEquals(response.state(), saved.getValue().getStateHash(),
                "в базе должен лежать хэш, а не сам state из браузера");
        assertEquals(ExternalMailOAuthService.sha256Hex(response.state()), saved.getValue().getStateHash());
        assertEquals(OWNER, saved.getValue().getOwnerId());
        assertEquals("GOOGLE", saved.getValue().getProvider());
        assertNotEquals("verifier-1", saved.getValue().getCodeVerifierEnc(),
                "PKCE-verifier хранится зашифрованным");
    }

    @Test
    void callbackCreatesConnectionAndRedirectsToApplication() throws Exception {
        ExternalMailOAuthState row = state("state-1", "/mail/inbox");
        when(states.findForUpdate(ExternalMailOAuthService.sha256Hex("state-1"))).thenReturn(Optional.of(row));
        ExternalMailOAuthClient.Tokens tokens = new ExternalMailOAuthClient.Tokens("at", "rt",
                Instant.now().plusSeconds(3600), "https://mail.google.com/", "id-token");
        when(client.exchange(eq(ExternalMailOAuthProvider.GOOGLE), eq("code-1"), any())).thenReturn(tokens);
        when(client.identityEmail(ExternalMailOAuthProvider.GOOGLE, tokens)).thenReturn("ivan@company.ru");

        String redirect = service.callback("code-1", "state-1", null);

        assertTrue(redirect.startsWith("https://mail.example.test/mail/inbox?oauth=connected&connection="), redirect);
        assertTrue(redirect.contains("provider=GOOGLE"), redirect);
        assertNotNull(row.getUsedAt(), "state гасится при первом использовании");
        ArgumentCaptor<ExternalMailOAuthConnection> saved = ArgumentCaptor.forClass(ExternalMailOAuthConnection.class);
        org.mockito.Mockito.verify(connections).saveAndFlush(saved.capture());
        assertEquals("ivan@company.ru", saved.getValue().getEmail());
        assertEquals("GOOGLE", saved.getValue().getProvider());
        assertNotEquals("at", saved.getValue().getAccessTokenEnc(), "токен доступа хранится зашифрованным");
    }

    @Test
    void reusedOrExpiredStateDoesNotCreateSecondConnection() {
        ExternalMailOAuthState used = state("state-2", "/mail");
        used.setUsedAt(Instant.now());
        when(states.findForUpdate(ExternalMailOAuthService.sha256Hex("state-2"))).thenReturn(Optional.of(used));

        String redirect = service.callback("code", "state-2", null);

        assertTrue(redirect.contains("oauth=error&reason=state_expired"), redirect);
        org.mockito.Mockito.verify(connections, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void unknownStateIsRejected() {
        when(states.findForUpdate(any())).thenReturn(Optional.empty());

        String redirect = service.callback("code", "unknown", null);

        assertTrue(redirect.endsWith("/mail?oauth=error&reason=invalid_state"), redirect);
    }

    @Test
    void userRefusalIsReportedAsAccessDenied() {
        ExternalMailOAuthState row = state("state-3", "/mail");
        when(states.findForUpdate(ExternalMailOAuthService.sha256Hex("state-3"))).thenReturn(Optional.of(row));

        String redirect = service.callback(null, "state-3", "access_denied");

        assertTrue(redirect.contains("oauth=error&reason=access_denied"), redirect);
    }

    @Test
    void returnPathCannotPointToAnotherHost() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.start(ACTOR, PERMISSIONS,
                        new ExternalMailOAuthDtos.AuthorizeRequest("GOOGLE", null, "https://evil.example/steal")));

        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void unknownProviderAndForeignOwnerAreRejected() {
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.start(ACTOR, PERMISSIONS,
                        new ExternalMailOAuthDtos.AuthorizeRequest("YAHOO", null, "/mail"))).getStatusCode().value());

        UUID other = UUID.randomUUID();
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.start(ACTOR, PERMISSIONS,
                        new ExternalMailOAuthDtos.AuthorizeRequest("GOOGLE", other, "/mail"))).getStatusCode().value());
    }

    @Test
    void claimBindsConsentToOwnerProviderAndAddress() throws Exception {
        UUID connectionId = UUID.randomUUID();
        ExternalMailOAuthConnection connection = new ExternalMailOAuthConnection();
        connection.setId(connectionId);
        connection.setOwnerId(OWNER);
        connection.setProvider("GOOGLE");
        connection.setEmail("ivan@company.ru");
        connection.setAccessTokenEnc("enc-at");
        connection.setRefreshTokenEnc("enc-rt");
        connection.setExpiresAt(Instant.now().plusSeconds(3600));
        connection.setCreatedAt(Instant.now());
        when(connections.findOpenForUpdate(connectionId, OWNER)).thenReturn(Optional.of(connection));

        ExternalMailOAuthService.Claimed claimed = service.claim(OWNER, connectionId,
                ExternalMailOAuthProvider.GOOGLE, "IVAN@company.ru");

        assertEquals("enc-at", claimed.accessTokenEnc());
        assertEquals("enc-rt", claimed.refreshTokenEnc());
        assertNotNull(connection.getConsumedAt());

        ExternalMailOAuthConnection foreign = new ExternalMailOAuthConnection();
        foreign.setId(connectionId);
        foreign.setOwnerId(OWNER);
        foreign.setProvider("GOOGLE");
        foreign.setEmail("ivan@company.ru");
        foreign.setCreatedAt(Instant.now());
        foreign.setExpiresAt(Instant.now().plusSeconds(3600));
        when(connections.findOpenForUpdate(connectionId, OWNER)).thenReturn(Optional.of(foreign));

        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.claim(OWNER, connectionId, ExternalMailOAuthProvider.GOOGLE, "other@company.ru"))
                .getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.claim(OWNER, connectionId, ExternalMailOAuthProvider.MICROSOFT, "ivan@company.ru"))
                .getStatusCode().value());
    }

    @Test
    void expiredConnectionIsNotAccepted() {
        UUID connectionId = UUID.randomUUID();
        ExternalMailOAuthConnection connection = new ExternalMailOAuthConnection();
        connection.setId(connectionId);
        connection.setOwnerId(OWNER);
        connection.setProvider("GOOGLE");
        connection.setEmail("ivan@company.ru");
        connection.setCreatedAt(Instant.now().minusSeconds(3600));
        connection.setExpiresAt(Instant.now().plusSeconds(3600));
        when(connections.findOpenForUpdate(connectionId, OWNER)).thenReturn(Optional.of(connection));

        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.claim(OWNER, connectionId, ExternalMailOAuthProvider.GOOGLE, "ivan@company.ru"))
                .getStatusCode().value());
    }

    @Test
    void providersAreHiddenUntilClientAndRedirectAreConfigured() {
        var providers = service.providers();

        assertEquals(2, providers.size());
        assertTrue(providers.stream().allMatch(ExternalMailOAuthDtos.ProviderView::enabled));
    }

    @Test
    void providersDisabledWithoutConfiguration() {
        ExternalMailOAuthProperties empty = new ExternalMailOAuthProperties("", "", "", "", "common", "", 5, 10, 30);
        ExternalMailOAuthService unconfigured = new ExternalMailOAuthService(empty, client, cipher, states,
                connections, mock(AuthUserClient.class));

        assertTrue(unconfigured.providers().stream().noneMatch(ExternalMailOAuthDtos.ProviderView::enabled));
    }
}
