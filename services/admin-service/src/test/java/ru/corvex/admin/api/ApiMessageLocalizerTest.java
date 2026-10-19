package ru.corvex.admin.api;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
