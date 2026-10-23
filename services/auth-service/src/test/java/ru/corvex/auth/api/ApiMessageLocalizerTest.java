package ru.corvex.auth.api;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiMessageLocalizerTest {

    @Test
    void translatesExactDynamicAndValidationMessagesToEnglish() {
        assertEquals("Incorrect username or password",
                ApiMessageLocalizer.localize("Неверный логин или пароль", Locale.ENGLISH));
        assertEquals("Too many failed attempts. Try again in 15 minutes",
                ApiMessageLocalizer.localize("Слишком много неверных попыток. Повторите через 15 минут", Locale.ENGLISH));
        assertEquals("username: enter a username",
                ApiMessageLocalizer.localize("username: укажите логин", Locale.ENGLISH));
    }

    @Test
    void keepsRussianCanonicalTextAndTranslatesBeanValidationDefaults() {
        Locale russian = Locale.forLanguageTag("ru");
        assertEquals("Неверный логин или пароль",
                ApiMessageLocalizer.localize("Неверный логин или пароль", russian));
        assertEquals("username: не должно быть пустым",
                ApiMessageLocalizer.localize("username: must not be blank", russian));
    }

    @Test
    void translatesParameterizedPasswordMessages() {
        assertEquals("The password must be at least 10 characters long",
                ApiMessageLocalizer.localize("Пароль должен быть не короче 10 символов", Locale.ENGLISH));
        assertEquals("The password must be at most 128 characters long",
                ApiMessageLocalizer.localize("Пароль должен быть не длиннее 128 символов", Locale.ENGLISH));
        assertEquals("Пароль должен быть не короче 10 символов",
                ApiMessageLocalizer.localize("Пароль должен быть не короче 10 символов", Locale.forLanguageTag("ru")));
    }

    @Test
    void translatesBeanValidationPatternsToRussian() {
        Locale russian = Locale.forLanguageTag("ru");
        assertEquals("обязательное поле", ApiMessageLocalizer.localize("must not be null", russian));
        assertEquals("размер должен быть от 2 до 64",
                ApiMessageLocalizer.localize("size must be between 2 and 64", russian));
        assertEquals("длина должна быть от 1 до 10",
                ApiMessageLocalizer.localize("length must be between 1 and 10", russian));
        assertEquals("некорректный e-mail",
                ApiMessageLocalizer.localize("must be a well-formed email address", russian));
        assertEquals("has an invalid format",
                ApiMessageLocalizer.localize("must match \"[0-9]{6}\"", Locale.ENGLISH));
    }

    @Test
    void translatesPrefixesAndLeavesUnknownDiagnostics() {
        assertEquals("This username is already taken: ivan",
                ApiMessageLocalizer.localize("Логин уже занят: ivan", Locale.ENGLISH));
        assertEquals("User not found: ivan",
                ApiMessageLocalizer.localize("Пользователь не найден: ivan", Locale.ENGLISH));
        assertEquals("field: untranslated diagnostic",
                ApiMessageLocalizer.localize("field: untranslated diagnostic", Locale.ENGLISH));
        assertEquals("Неизвестная диагностика",
                ApiMessageLocalizer.localize("Неизвестная диагностика", Locale.ENGLISH));
    }

    @Test
    void handlesMissingDetailAndLocaleDetection() {
        assertNull(ApiMessageLocalizer.localize(null, Locale.ENGLISH));
        assertEquals(" ", ApiMessageLocalizer.localize(" ", Locale.ENGLISH));
        assertTrue(ApiMessageLocalizer.isEnglish(Locale.US));
        assertFalse(ApiMessageLocalizer.isEnglish(Locale.forLanguageTag("ru")));
        assertFalse(ApiMessageLocalizer.isEnglish(null));
    }
}
