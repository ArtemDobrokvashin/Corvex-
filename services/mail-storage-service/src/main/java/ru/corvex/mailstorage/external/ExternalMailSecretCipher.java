package ru.corvex.mailstorage.external;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM для паролей приложений внешних ящиков. Формат: base64(iv).base64(ciphertext+tag).
 * Fail-closed: без валидного 32-байтного ключа создание/использование аккаунта возвращает 503,
 * а не сохраняет секрет открытым текстом.
 */
@Component
public class ExternalMailSecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ExternalMailSecretCipher(@Value("${corvex.external-mail.enc-key:${CORVEX_EXTERNAL_MAIL_ENC_KEY:}}") String base64Key) {
        SecretKeySpec parsed = null;
        if (base64Key != null && !base64Key.isBlank()) {
            try {
                byte[] raw = Base64.getDecoder().decode(base64Key.trim());
                if (raw.length == 32) {
                    parsed = new SecretKeySpec(raw, "AES");
                }
            } catch (IllegalArgumentException ignored) {
                parsed = null;
            }
        }
        this.key = parsed;
    }

    public boolean configured() {
        return key != null;
    }

    public void requireConfigured() {
        if (!configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "External Mail Connector не настроен: задайте CORVEX_EXTERNAL_MAIL_ENC_KEY (Base64, 32 байта)");
        }
    }

    public String encrypt(String plain) {
        requireConfigured();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(encrypted);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось зашифровать секрет внешнего ящика", e);
        }
    }

    public String decrypt(String stored) {
        requireConfigured();
        try {
            String[] parts = stored.split("\\.", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("bad format");
            }
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось расшифровать секрет внешнего ящика: ключ изменён или данные повреждены", e);
        }
    }
}
