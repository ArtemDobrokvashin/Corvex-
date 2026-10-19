package ru.corvex.auth.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Локализация публичных ProblemDetail на границе HTTP.
 *
 * Доменная логика исторически бросает канонические русские сообщения. Этот
 * класс не меняет бизнес-код и логи: он переводит известные detail по
 * Accept-Language, включая динамические шаблоны и поля Bean Validation.
 * Неизвестный detail возвращается без изменений, чтобы не терять диагностику.
 */
final class ApiMessageLocalizer {

    private record LocalizedPattern(Pattern pattern, String english, String russian) {
    }

    private static final Map<String, String> ENGLISH = Map.ofEntries(
            Map.entry("Недостаточно прав для этого действия", "You do not have permission for this action"),
            Map.entry("Некорректный запрос", "Invalid request"),
            Map.entry("Некорректный JSON запроса", "Invalid request JSON"),
            Map.entry("Нет аутентификации", "Authentication is required"),
            Map.entry("Некорректный токен", "Invalid token"),
            Map.entry("Неверный логин или пароль", "Incorrect username or password"),
            Map.entry("Учётная запись заблокирована или отключена. Обратитесь к администратору",
                    "This account is blocked or disabled. Contact an administrator"),
            Map.entry("Второй фактор временно недоступен, попробуйте позже",
                    "The second factor is temporarily unavailable. Please try again later"),
            Map.entry("Сессия входа истекла, войдите заново", "Your sign-in session has expired. Please sign in again"),
            Map.entry("Неверный код подтверждения", "Incorrect confirmation code"),
            Map.entry("Учётная запись заблокирована или отключена", "This account is blocked or disabled"),
            Map.entry("Нет refresh-токена", "No refresh token"),
            Map.entry("Refresh-токен недействителен", "The refresh token is invalid"),
            Map.entry("Refresh-токен истёк", "The refresh token has expired"),
            Map.entry("Пользователь не существует", "The user does not exist"),
            Map.entry("Учётная запись недоступна", "This account is unavailable"),
            Map.entry("Текущий пароль неверен", "The current password is incorrect"),
            Map.entry("Новый пароль должен отличаться от текущего", "The new password must differ from the current one"),
            Map.entry("Сначала выполните настройку 2FA (totp/setup)", "Set up 2FA first (totp/setup)"),
            Map.entry("2FA не включена", "2FA is not enabled"),
            Map.entry("2FA уже включена. Сначала отключите её текущим кодом",
                    "2FA is already enabled. Disable it with a current code first"),
            Map.entry("Пароль приложения не найден", "App password not found"),
            Map.entry("укажите логин", "enter a username"),
            Map.entry("укажите пароль", "enter a password"),
            Map.entry("нет mfaTicket", "mfaTicket is missing"),
            Map.entry("код — 6 цифр", "the code must contain 6 digits"),
            Map.entry("укажите название", "enter a name"),
            Map.entry("неккоректный e-mail", "invalid email address"),
            Map.entry("некорректный e-mail", "invalid email address"),
            Map.entry("Пароль должен содержать буквы и цифры", "The password must contain letters and digits"),
            Map.entry("Запись с такими данными уже существует", "A record with this data already exists"),
            Map.entry("auth-service недоступен", "auth-service is unavailable"));

    private static final Map<String, String> ENGLISH_PREFIXES = Map.ofEntries(
            Map.entry("auth-service недоступен: ", "auth-service is unavailable: "),
            Map.entry("Логин уже занят: ", "This username is already taken: "),
            Map.entry("E-mail уже занят: ", "This email address is already taken: "),
            Map.entry("Пользователь не найден: ", "User not found: "),
            Map.entry("Неизвестный статус пользователя: ", "Unknown user status: "));

    private static final List<LocalizedPattern> PATTERNS = List.of(
            new LocalizedPattern(
                    Pattern.compile("^Слишком много неверных попыток\\. Повторите через (\\d+) минут$"),
                    "Too many failed attempts. Try again in $1 minutes", null),
            new LocalizedPattern(
                    Pattern.compile("^Пароль должен быть не короче (\\d+) символов$"),
                    "The password must be at least $1 characters long", null),
            new LocalizedPattern(
                    Pattern.compile("^Пароль должен быть не длиннее (\\d+) символов$"),
                    "The password must be at most $1 characters long", null),
            new LocalizedPattern(Pattern.compile("(?i)^must not be blank$"),
                    "must not be blank", "не должно быть пустым"),
            new LocalizedPattern(Pattern.compile("(?i)^must not be null$"),
                    "must not be null", "обязательное поле"),
            new LocalizedPattern(Pattern.compile("(?i)^size must be between (\\d+) and (\\d+)$"),
                    "size must be between $1 and $2", "размер должен быть от $1 до $2"),
            new LocalizedPattern(Pattern.compile("(?i)^length must be between (\\d+) and (\\d+)$"),
                    "length must be between $1 and $2", "длина должна быть от $1 до $2"),
            new LocalizedPattern(Pattern.compile("(?i)^must be a well-formed email address$"),
                    "must be a well-formed email address", "некорректный e-mail"),
            new LocalizedPattern(Pattern.compile("(?i)^must be greater than or equal to 0$"),
                    "must be greater than or equal to 0", "должно быть не меньше 0"),
            new LocalizedPattern(Pattern.compile("(?i)^must match \".*\"$"),
                    "has an invalid format", "не соответствует требуемому формату"));

    private ApiMessageLocalizer() {
    }

    static String localize(String detail, Locale locale) {
        if (detail == null || detail.isBlank()) {
            return detail;
        }

        int separator = detail.indexOf(": ");
        if (separator > 0) {
            String field = detail.substring(0, separator);
            String message = detail.substring(separator + 2);
            if (field.matches("[A-Za-z][A-Za-z0-9_]*") && !message.isBlank()) {
                String localizedMessage = localize(message, locale);
                if (!localizedMessage.equals(message)) {
                    return field + ": " + localizedMessage;
                }
            }
        }

        boolean english = isEnglish(locale);
        for (LocalizedPattern localizedPattern : PATTERNS) {
            Matcher matcher = localizedPattern.pattern().matcher(detail);
            if (matcher.matches()) {
                String replacement = english ? localizedPattern.english() : localizedPattern.russian();
                return replacement == null ? detail : matcher.replaceAll(replacement);
            }
        }

        if (!english) {
            return detail;
        }
        String exact = ENGLISH.get(detail);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> prefix : ENGLISH_PREFIXES.entrySet()) {
            if (detail.startsWith(prefix.getKey())) {
                return prefix.getValue() + localize(detail.substring(prefix.getKey().length()), locale);
            }
        }
        return detail;
    }

    static boolean isEnglish(Locale locale) {
        return locale != null && Locale.ENGLISH.getLanguage().equals(locale.getLanguage());
    }
}
