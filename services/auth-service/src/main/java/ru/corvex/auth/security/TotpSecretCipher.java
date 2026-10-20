package ru.corvex.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Шифрование TOTP-секретов в auth_db (AES-GCM, случайный IV на каждую запись).
 * Ключ — CORVEX_TOTP_ENC_KEY (Base64, 32 байта, генерирует first-run). Без ключа
 * поднимается эфемерный на процесс с WARN: после рестарта секреты не расшифруются
 * и пользователям придётся привязать аутентификатор заново — для продакшена недопустимо.
 */
@Component
public class TotpSecretCipher {

    private static final Logger log = LoggerFactory.getLogger(TotpSecretCipher.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public TotpSecretCipher(@Value("${corvex.totp.enc-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            try {
                KeyGenerator gen = KeyGenerator.getInstance("AES");
                gen.init(256);
                this.key = gen.generateKey();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("AES недоступен в JRE", e);
            }
            log.warn("CORVEX_TOTP_ENC_KEY is not set — using EPHEMERAL key: TOTP secrets will not survive restart!");
        } else {
            byte[] raw = Base64.getDecoder().decode(base64Key.trim());
            if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
                throw new IllegalStateException("CORVEX_TOTP_ENC_KEY must decode to 16/24/32 bytes, got " + raw.length);
            }
            this.key = new SecretKeySpec(raw, "AES");
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось зашифровать TOTP-секрет", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось расшифровать TOTP-секрет (сменился ключ шифрования?)", e);
        }
    }
}
