package ru.corvex.admin.api;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiMessageLocalizerTest {

    @Test
    void translatesAdminAndPropagatedAuthErrors() {
        assertEquals("You do not have permission for this action",
                ApiMessageLocalizer.localize("Недостаточно прав для этого действия", Locale.ENGLISH));
        assertEquals("This username is already taken: i.ivanov",
                ApiMessageLocalizer.localize("Логин уже занят: i.ivanov", Locale.ENGLISH));
        assertEquals("groupCodes: groupCodes is required (it may be empty)",
                ApiMessageLocalizer.localize("groupCodes: groupCodes обязателен (может быть пустым)", Locale.ENGLISH));
    }

    @Test
    void translatesValidationDefaultsToRussian() {
        assertEquals("quotaBytes: должно быть не меньше 0",
                ApiMessageLocalizer.localize("quotaBytes: must be greater than or equal to 0", Locale.forLanguageTag("ru")));
    }

    @Test
    void translatesParameterizedRussianPatternsToEnglish() {
        assertEquals("Too many failed attempts. Try again in 15 minutes",
                ApiMessageLocalizer.localize(
                        "Слишком много неверных попыток. Повторите через 15 минут", Locale.ENGLISH));
        assertEquals("The password must be at least 12 characters long",
                ApiMessageLocalizer.localize(
                        "Пароль должен быть не короче 12 символов", Locale.ENGLISH));
        assertEquals("The password must be at most 128 characters long",
                ApiMessageLocalizer.localize(
                        "Пароль должен быть не длиннее 128 символов", Locale.ENGLISH));
        assertEquals("Пароль должен быть не короче 12 символов",
                ApiMessageLocalizer.localize(
                        "Пароль должен быть не короче 12 символов", Locale.forLanguageTag("ru")));
    }

    @Test
    void translatesBeanValidationPatternsInBothDirections() {
        Locale russian = Locale.forLanguageTag("ru");
        assertEquals("не должно быть пустым",
                ApiMessageLocalizer.localize("MUST NOT BE BLANK", russian));
        assertEquals("обязательное поле",
                ApiMessageLocalizer.localize("must not be null", russian));
        assertEquals("размер должен быть от 2 до 64",
                ApiMessageLocalizer.localize("size must be between 2 and 64", russian));
        assertEquals("длина должна быть от 1 до 10",
                ApiMessageLocalizer.localize("length must be between 1 and 10", russian));
        assertEquals("некорректный e-mail",
                ApiMessageLocalizer.localize("must be a well-formed email address", russian));
        assertEquals("has an invalid format",
                ApiMessageLocalizer.localize("must match \"[A-Z]+\"", Locale.ENGLISH));
    }

    @Test
    void translatesPrefixesAndLeavesUnknownDetailsUnchanged() {
        assertEquals("Unknown setting: custom.key",
                ApiMessageLocalizer.localize("Неизвестная настройка: custom.key", Locale.ENGLISH));
        assertEquals("Invalid setting value mail.maxMessageSizeMb: abc",
                ApiMessageLocalizer.localize(
                        "Некорректное значение настройки mail.maxMessageSizeMb: abc", Locale.ENGLISH));
        assertEquals("auth-service is unavailable: connection refused",
                ApiMessageLocalizer.localize(
                        "auth-service недоступен: connection refused", Locale.ENGLISH));
        assertEquals("field: diagnostic without translation",
                ApiMessageLocalizer.localize(
                        "field: diagnostic without translation", Locale.ENGLISH));
        assertEquals("Непереведённая диагностика",
                ApiMessageLocalizer.localize("Непереведённая диагностика", Locale.ENGLISH));
    }

    @Test
    void handlesMissingTextAndLocaleDetection() {
        assertNull(ApiMessageLocalizer.localize(null, Locale.ENGLISH));
        assertEquals(" ", ApiMessageLocalizer.localize(" ", Locale.ENGLISH));
        assertTrue(ApiMessageLocalizer.isEnglish(Locale.US));
        assertFalse(ApiMessageLocalizer.isEnglish(Locale.forLanguageTag("ru")));
        assertFalse(ApiMessageLocalizer.isEnglish(null));
    }
}
