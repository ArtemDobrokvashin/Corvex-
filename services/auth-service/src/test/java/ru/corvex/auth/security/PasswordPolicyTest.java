package ru.corvex.auth.security;

import org.junit.jupiter.api.Test;

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
}
