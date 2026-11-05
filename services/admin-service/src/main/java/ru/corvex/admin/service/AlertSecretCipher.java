package ru.corvex.admin.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM для write-only секретов внешних alert-каналов. */
@Component
public class AlertSecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AlertSecretCipher(@Value("${corvex.alert.config-enc-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null; // Registry и env-секреты работают; запись нового UI-секрета честно вернёт 503.
            return;
        }
        final byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("CORVEX_ALERT_CONFIG_ENC_KEY must be Base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("CORVEX_ALERT_CONFIG_ENC_KEY must decode to exactly 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            throw new IllegalArgumentException("Alert secret must not be blank");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось зашифровать секрет alert-канала", e);
        }
    }

    public String decrypt(String stored) {
        try {
            String[] parts = stored.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid encrypted alert secret");
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);
            if (iv.length != IV_BYTES) throw new IllegalArgumentException("Invalid alert secret IV");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось расшифровать секрет alert-канала (проверьте ключ)", e);
        }
    }

    private SecretKeySpec requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "CORVEX_ALERT_CONFIG_ENC_KEY не настроен: добавьте Base64 из 32 случайных байт в .env");
        }
        return key;
    }
}
