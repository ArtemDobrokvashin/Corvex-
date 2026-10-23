package ru.corvex.auth.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthSecurityConfigTest {

    private final AuthSecurityConfig config = new AuthSecurityConfig();

    @Test
    void passwordEncoderUsesArgon2idAndVerifiesPasswords() {
        PasswordEncoder encoder = config.passwordEncoder();

        String hash = encoder.encode("Correct-Horse-9");

        assertTrue(hash.startsWith("$argon2id$"));
        assertNotEquals("Correct-Horse-9", hash);
        assertTrue(encoder.matches("Correct-Horse-9", hash));
        assertFalse(encoder.matches("Wrong-Horse-9", hash));
    }

    @Test
    void localeResolverDefaultsToRussianAndSupportsEnglish() {
        AcceptHeaderLocaleResolver resolver = assertInstanceOf(
                AcceptHeaderLocaleResolver.class, config.localeResolver());
        MockHttpServletRequest missing = new MockHttpServletRequest();
        MockHttpServletRequest english = new MockHttpServletRequest();
        english.addHeader("Accept-Language", "en-US,en;q=0.8");
        MockHttpServletRequest unsupported = new MockHttpServletRequest();
        unsupported.addHeader("Accept-Language", "fr-FR");

        assertEquals(List.of(Locale.forLanguageTag("ru"), Locale.ENGLISH),
                resolver.getSupportedLocales());
        assertEquals(Locale.forLanguageTag("ru"), resolver.resolveLocale(missing));
        assertEquals(Locale.ENGLISH, resolver.resolveLocale(english));
        assertEquals(Locale.forLanguageTag("ru"), resolver.resolveLocale(unsupported));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jwksControllerReturnsOnlyPublicKeyJson() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey publicKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .keyID("public-test-key")
                .build();
        var controller = new AuthSecurityConfig.JwksController(new JWKSet(publicKey));

        Map<String, Object> json = controller.jwks();
        List<Map<String, Object>> keys = (List<Map<String, Object>>) json.get("keys");

        assertEquals(1, keys.size());
        assertEquals("public-test-key", keys.get(0).get("kid"));
        assertEquals("RSA", keys.get(0).get("kty"));
        assertFalse(keys.get(0).containsKey("d"));
        assertFalse(keys.get(0).containsKey("p"));
        assertFalse(keys.get(0).containsKey("q"));
    }
}
