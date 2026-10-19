package ru.corvex.auth.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * TOTP по RFC 6238 (HMAC-SHA1, шаг 30 с, 6 цифр) — совместим с Google/Microsoft Authenticator,
 * Яндекс Ключом и т.п. Допускается окно ±1 шаг на расхождение часов клиента и сервера.
 * Секрет пользователя хранится в auth_db в зашифрованном виде (AES-GCM, ключ из .env).
 */
public final class TotpService {

    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpService() {
    }

    /** Генерирует новый Base32-секрет (160 бит) для привязки аутентификатора. */
    public static String generateSecret() {
        byte[] raw = new byte[20];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < raw.length; i += 5) {
            long chunk = 0;
            for (int j = 0; j < 5; j++) {
                chunk = (chunk << 8) | (raw[i + j] & 0xFFL);
            }
            for (int j = 7; j >= 0; j--) {
                sb.append(BASE32.charAt((int) ((chunk >>> (j * 5)) & 0x1F)));
            }
        }
        return sb.toString();
    }

    /** Проверяет код с окном ±1 шаг. Сравнение — не по времени не чувствительно для 6 цифр при rate-limit на API. */
    public static boolean verify(String base32Secret, String code, Instant now) {
        if (code == null || code.length() != DIGITS) {
            return false;
        }
        long counter = now.getEpochSecond() / STEP_SECONDS;
        for (long drift = -1; drift <= 1; drift++) {
            if (hotp(decodeBase32(base32Secret), counter + drift).equals(code)) {
                return true;
            }
        }
        return false;
    }

    static String hotp(byte[] key, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format("%06d", binary % 1_000_000);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA1 недоступен в JRE", e);
        }
    }

    static byte[] decodeBase32(String s) {
        String clean = s.replace("=", "").toUpperCase();
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0, bits = 0, idx = 0;
        for (char c : clean.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("Неверный символ Base32: " + c);
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[idx++] = (byte) ((buffer >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out;
    }
}
