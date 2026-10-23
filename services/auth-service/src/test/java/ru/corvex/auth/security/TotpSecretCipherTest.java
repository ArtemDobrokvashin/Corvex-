package ru.corvex.auth.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TotpSecretCipherTest {

    @Test
    void encryptDecryptRoundTripWithConfiguredAes256Key() {
        TotpSecretCipher cipher = new TotpSecretCipher(base64Key(32, 1));

        String stored = cipher.encrypt("JBSWY3DPEHPK3PXP");

        assertNotEquals("JBSWY3DPEHPK3PXP", stored);
        assertEquals("JBSWY3DPEHPK3PXP", cipher.decrypt(stored));
    }

    @Test
    void randomIvMakesRepeatedEncryptionDifferent() {
        TotpSecretCipher cipher = new TotpSecretCipher(base64Key(32, 7));

        assertNotEquals(cipher.encrypt("secret"), cipher.encrypt("secret"));
    }

    @Test
    void blankKeyCreatesUsableEphemeralCipher() {
        TotpSecretCipher cipher = new TotpSecretCipher(" ");

        assertEquals("secret", cipher.decrypt(cipher.encrypt("secret")));
        TotpSecretCipher nullKeyCipher = new TotpSecretCipher(null);
        assertEquals("secret", nullKeyCipher.decrypt(nullKeyCipher.encrypt("secret")));
    }

    @Test
    void acceptsAes128And192KeysAndTrimsConfiguredValue() {
        assertEquals("x", new TotpSecretCipher(" " + base64Key(16, 2) + " ")
                .decrypt(new TotpSecretCipher(base64Key(16, 2)).encrypt("x")));
        TotpSecretCipher aes192 = new TotpSecretCipher(base64Key(24, 3));
        assertEquals("x", aes192.decrypt(aes192.encrypt("x")));
    }

    @Test
    void rejectsInvalidKeyLengthAndInvalidBase64() {
        String fiveBytes = Base64.getEncoder().encodeToString("12345".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> new TotpSecretCipher(fiveBytes));
        assertThrows(IllegalArgumentException.class, () -> new TotpSecretCipher("not*base64"));
    }

    @Test
    void tamperedOrTruncatedCiphertextFailsClosed() {
        TotpSecretCipher cipher = new TotpSecretCipher(base64Key(32, 11));
        byte[] bytes = Base64.getDecoder().decode(cipher.encrypt("secret"));
        bytes[bytes.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(bytes);

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(
                Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("not-base64*"));
    }

    @Test
    void differentKeyCannotDecryptStoredSecret() {
        String stored = new TotpSecretCipher(base64Key(32, 21)).encrypt("secret");

        assertThrows(IllegalStateException.class,
                () -> new TotpSecretCipher(base64Key(32, 22)).decrypt(stored));
    }

    private String base64Key(int length, int seed) {
        byte[] key = new byte[length];
        for (int i = 0; i < length; i++) {
            key[i] = (byte) (seed + i);
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
