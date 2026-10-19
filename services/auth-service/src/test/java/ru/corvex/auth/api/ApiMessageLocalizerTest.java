package ru.corvex.auth.api;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
