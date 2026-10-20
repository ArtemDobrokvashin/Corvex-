package ru.corvex.auth.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Векторы RFC 6238 (секрет ASCII "12345678901234567890", SHA-1), урезанные до 6 цифр. */
class TotpServiceTest {

    private static final String RFC_SECRET_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void rfc6238Vectors() {
        assertTrue(TotpService.verify(RFC_SECRET_BASE32, "287082", Instant.ofEpochSecond(59)));
        assertTrue(TotpService.verify(RFC_SECRET_BASE32, "081804", Instant.ofEpochSecond(1111111109L)));
        assertTrue(TotpService.verify(RFC_SECRET_BASE32, "050471", Instant.ofEpochSecond(1111111111L)));
        assertTrue(TotpService.verify(RFC_SECRET_BASE32, "005924", Instant.ofEpochSecond(1234567890L)));
    }

    @Test
    void windowAllowsOneStepDriftButNotRandomCode() {
        // Код на T=59 валиден и в соседних шагах (окно ±30 c)
        assertTrue(TotpService.verify(RFC_SECRET_BASE32, "287082", Instant.ofEpochSecond(59 + 30)));
        assertFalse(TotpService.verify(RFC_SECRET_BASE32, "000000", Instant.ofEpochSecond(59)));
        assertFalse(TotpService.verify(RFC_SECRET_BASE32, "287082", Instant.ofEpochSecond(59 + 600)));
    }

    @Test
    void generatedSecretVerifiesAgainstItself() {
        String secret = TotpService.generateSecret();
        String code = TotpService.hotp(TotpService.decodeBase32(secret), 1000L);
        assertTrue(TotpService.verify(secret, code, Instant.ofEpochSecond(1000L * 30)));
    }
}
