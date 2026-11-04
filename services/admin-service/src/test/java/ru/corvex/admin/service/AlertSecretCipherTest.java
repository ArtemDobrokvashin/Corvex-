package ru.corvex.admin.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlertSecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void roundTripUsesFreshIvAndDetectsTampering() {
        AlertSecretCipher cipher = new AlertSecretCipher(KEY);

        String first = cipher.encrypt("smtp-secret");
        String second = cipher.encrypt("smtp-secret");

        assertNotEquals(first, second);
        assertEquals("smtp-secret", cipher.decrypt(first));
        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(first.substring(0, first.length() - 2) + "xx"));
    }

    @Test
    void missingOrInvalidKeyFailsClosedOnlyWhenSecretOperationIsRequested() {
        AlertSecretCipher missing = new AlertSecretCipher("");
        assertThrows(IllegalStateException.class, () -> missing.encrypt("secret"));
        assertThrows(IllegalStateException.class, () -> new AlertSecretCipher("not-base64"));
        assertThrows(IllegalStateException.class,
                () -> new AlertSecretCipher(Base64.getEncoder().encodeToString(new byte[16])));
    }
}
