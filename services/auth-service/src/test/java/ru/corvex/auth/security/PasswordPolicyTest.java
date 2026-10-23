package ru.corvex.auth.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PasswordPolicyTest {

    @Test
    void acceptsReasonablePassword() {
        assertNull(PasswordPolicy.validate("Correct-Horse-9"));
    }

    @Test
    void rejectsShortAndDigitless() {
        assertNotNull(PasswordPolicy.validate("short1"));
        assertNotNull(PasswordPolicy.validate("onlylettershere"));
        assertNotNull(PasswordPolicy.validate("12345678901"));
        assertNotNull(PasswordPolicy.validate(null));
    }

    @Test
    void enforcesExactLengthBoundaries() {
        assertNull(PasswordPolicy.validate("a".repeat(9) + "1"));
        assertNull(PasswordPolicy.validate("a".repeat(127) + "1"));
        assertEquals("Пароль должен быть не короче 10 символов",
                PasswordPolicy.validate("a".repeat(8) + "1"));
        assertEquals("Пароль должен быть не длиннее 128 символов",
                PasswordPolicy.validate("a".repeat(128) + "1"));
        assertEquals("Пароль должен содержать буквы и цифры",
                PasswordPolicy.validate("!".repeat(12)));
    }
}
