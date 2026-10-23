package ru.corvex.auth.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtKeyAndTokenServiceTest {

    private final JwtKeyConfig config = new JwtKeyConfig();

    @TempDir
    Path tempDir;

    @Test
    void loadsPkcs8PemPrivateKeyAndDerivesMatchingPublicKey() throws Exception {
        KeyPair keyPair = generateKeyPair();
        Path file = tempDir.resolve("jwt_private.pem");
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(keyPair.getPrivate().getEncoded());
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + encoded
                + "\n-----END PRIVATE KEY-----\n");

        RSAKey loaded = config.rsaKey(file.toString());

        assertEquals(JwtKeyConfig.KEY_ID, loaded.getKeyID());
        assertEquals(((RSAPublicKey) keyPair.getPublic()).getModulus(),
                loaded.toRSAPublicKey().getModulus());
        assertNotNull(loaded.toRSAPrivateKey());
    }

    @Test
    void generatesEphemeralKeyWhenFileIsMissing() throws Exception {
        RSAKey generated = config.rsaKey(tempDir.resolve("missing.pem").toString());

        assertTrue(generated.getKeyID().startsWith("ephemeral-"));
        assertNotNull(generated.toRSAPublicKey());
        assertNotNull(generated.toRSAPrivateKey());
    }

    @Test
    void tokenServiceIssuesVerifiableRs256TokenWithAccessClaims() throws Exception {
        RSAKey rsaKey = rsaKey();
        TokenService tokens = new TokenService(config.jwtEncoder(rsaKey), Duration.ofMinutes(15));
        UUID userId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        Instant before = Instant.now();

        TokenService.AccessToken issued = tokens.issueAccessToken(
                userId, "ivan", List.of("USER", "MANAGER"),
                List.of("MAIL_READ_OWN", "ANALYTICS_VIEW_OWN_UNIT"), true);
        Jwt decoded = config.jwtDecoder(rsaKey).decode(issued.value());

        assertEquals("RS256", decoded.getHeaders().get("alg"));
        assertEquals("corvex-auth", decoded.getClaimAsString("iss"));
        assertEquals(userId.toString(), decoded.getSubject());
        assertEquals("ivan", decoded.getClaimAsString("preferred_username"));
        assertEquals(List.of("USER", "MANAGER"), decoded.getClaimAsStringList("groups"));
        assertEquals(List.of("MAIL_READ_OWN", "ANALYTICS_VIEW_OWN_UNIT"),
                decoded.getClaimAsStringList("permissions"));
        assertEquals(List.of("pwd", "otp"), decoded.getClaimAsStringList("amr"));
        assertNotNull(decoded.getId());
        assertEquals(issued.expiresAt().getEpochSecond(), decoded.getExpiresAt().getEpochSecond());
        assertFalse(decoded.getIssuedAt().isBefore(before.minusSeconds(1)));
    }

    @Test
    void passwordOnlyTokenHasPwdAuthenticationMethod() throws Exception {
        RSAKey rsaKey = rsaKey();
        TokenService tokens = new TokenService(config.jwtEncoder(rsaKey), Duration.ofMinutes(5));

        var issued = tokens.issueAccessToken(
                UUID.randomUUID(), "anna", List.of(), List.of(), false);
        Jwt decoded = config.jwtDecoder(rsaKey).decode(issued.value());

        assertEquals(List.of("pwd"), decoded.getClaimAsStringList("amr"));
    }

    @Test
    void jwkSetExposesOnlyPublicKeyMaterial() throws Exception {
        RSAKey rsaKey = rsaKey();

        JWKSet jwkSet = config.jwkSet(rsaKey);

        assertEquals(1, jwkSet.getKeys().size());
        assertFalse(jwkSet.getKeys().get(0).isPrivate());
        assertEquals("test-key", jwkSet.getKeys().get(0).getKeyID());
        assertFalse(jwkSet.toJSONObject().toString().contains("\"d\""));
    }

    private RSAKey rsaKey() throws Exception {
        KeyPair keyPair = generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test-key")
                .build();
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
