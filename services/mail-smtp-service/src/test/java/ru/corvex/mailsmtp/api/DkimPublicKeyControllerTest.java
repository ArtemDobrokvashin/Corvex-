package ru.corvex.mailsmtp.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.corvex.mailsmtp.outbound.DkimKeyRing;
import ru.corvex.mailsmtp.outbound.DkimSigner;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Только unit-проверка контроллера: HTTP-сервер и James не поднимаются. */
class DkimPublicKeyControllerTest {

    private static DkimSigner signer(String... domains) throws Exception {
        java.util.List<DkimKeyRing.Key> keys = new java.util.ArrayList<>();
        for (String domain : domains) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            keys.add(new DkimKeyRing.Key(domain, "corvex1", pair.getPrivate()));
        }
        return new DkimSigner(DkimKeyRing.of(keys));
    }

    private static final String TOKEN = "t".repeat(40);

    @Test
    void rejectsMissingAndWrongToken() throws Exception {
        var controller = new DkimPublicKeyController(signer("company.local"), TOKEN);

        assertEquals(HttpStatus.UNAUTHORIZED, controller.publicKey(null, "company.local").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.publicKey("wrong", "company.local").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.keys("wrong").getStatusCode());
    }

    @Test
    void emptyConfiguredTokenFailsClosed() throws Exception {
        var controller = new DkimPublicKeyController(signer("company.local"), " ");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.publicKey("", "company.local").getStatusCode());
    }

    @Test
    void returnsKeyOnlyForTheRequestedDomain() throws Exception {
        var controller = new DkimPublicKeyController(signer("company.local", "branch.example"), TOKEN);

        ResponseEntity<Map<String, String>> found = controller.publicKey(TOKEN, "branch.example");
        assertEquals(HttpStatus.OK, found.getStatusCode());
        assertEquals("branch.example", found.getBody().get("domain"));
        assertTrue(found.getBody().get("publicKeyBase64").length() > 100);

        assertEquals(HttpStatus.NOT_FOUND, controller.publicKey(TOKEN, "other.example").getStatusCode());
    }

    @Test
    void withoutDomainOnlyASingleKeyIsReturned() throws Exception {
        var single = new DkimPublicKeyController(signer("company.local"), TOKEN);
        assertEquals(HttpStatus.OK, single.publicKey(TOKEN, null).getStatusCode());

        var multiple = new DkimPublicKeyController(signer("company.local", "branch.example"), TOKEN);
        assertEquals(HttpStatus.NOT_FOUND, multiple.publicKey(TOKEN, null).getStatusCode());
        assertEquals(HttpStatus.OK, multiple.keys(TOKEN).getStatusCode());
    }

    @Test
    void keysListNeverExposesPrivateMaterial() throws Exception {
        var controller = new DkimPublicKeyController(signer("company.local"), TOKEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller.keys(TOKEN).getBody();
        String rendered = String.valueOf(body);
        assertTrue(rendered.contains("company.local"));
        assertFalse(rendered.contains("privateKey"));
        assertFalse(rendered.contains("BEGIN PRIVATE KEY"));
    }
}
