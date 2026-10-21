package ru.corvex.mailstorage.external;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalMailSecretCipherTest {

    private static String key32() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) (i + 1);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void encryptsWithRandomIvAndDecrypts() {
        ExternalMailSecretCipher cipher = new ExternalMailSecretCipher(key32());
        String first = cipher.encrypt("app-password");
        String second = cipher.encrypt("app-password");

        assertTrue(cipher.configured());
        assertNotEquals(first, second);
        assertEquals("app-password", cipher.decrypt(first));
        assertEquals("app-password", cipher.decrypt(second));
    }

    @Test
    void missingKeyFailsClosed() {
        ExternalMailSecretCipher cipher = new ExternalMailSecretCipher("");
        assertThrows(ResponseStatusException.class, () -> cipher.encrypt("secret"));
    }

    @Test
    void wrongKeyCannotDecrypt() {
        ExternalMailSecretCipher cipher = new ExternalMailSecretCipher(key32());
        String stored = cipher.encrypt("secret");
        byte[] other = new byte[32];
        other[0] = 9;
        ExternalMailSecretCipher wrong = new ExternalMailSecretCipher(Base64.getEncoder().encodeToString(other));
        assertThrows(IllegalStateException.class, () -> wrong.decrypt(stored));
    }
}
