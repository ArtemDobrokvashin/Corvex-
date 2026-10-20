package ru.corvex.admin.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Локализация публичных ProblemDetail на границе HTTP.
 *
 * Доменная логика и auth-service бросают канонические русские сообщения.
 * Перевод выполняется здесь по Accept-Language, включая ошибки, которые
 * admin-service пробрасывает из внутреннего API auth-service. Неизвестный
 * detail возвращается без изменений, чтобы не терять диагностику.
 */
final class ApiMessageLocalizer {

    private record LocalizedPattern(Pattern pattern, String english, String russian) {
    }

    private static final Map<String, String> ENGLISH = Map.ofEntries(
            Map.entry("Недостаточно прав для этого действия", "You do not have permission for this action"),
            Map.entry("Некорректный запрос", "Invalid request"),
            Map.entry("Некорректный JSON запроса", "Invalid request JSON"),
            Map.entry("groupCodes обязателен (может быть пустым)", "groupCodes is required (it may be empty)"),
            Map.entry("код группы: латиница, цифры, _ и -", "group code: Latin letters, digits, _ and -"),
            Map.entry("permissions обязателен (может быть пустым)", "permissions is required (it may be empty)"),
            Map.entry("auth-service недоступен", "auth-service is unavailable"),
            Map.entry("Права группы SUPER_ADMIN не редактируются (страховка от самоблокировки)",
                    "SUPER_ADMIN permissions cannot be edited (protection against locking the system out)"),
            Map.entry("Нельзя оставить систему без супер-администратора",
                    "The system cannot be left without a super administrator"),
            Map.entry("Код группы не указан", "No group code was provided"),
            Map.entry("Пустой набор настроек", "The settings set is empty"),
            Map.entry("Неверный логин или пароль", "Incorrect username or password"),
            Map.entry("Учётная запись заблокирована или отключена. Обратитесь к администратору",
                    "This account is blocked or disabled. Contact an administrator"),
            Map.entry("Пользователь не существует", "The user does not exist"),
            Map.entry("Текущий пароль неверен", "The current password is incorrect"),
            Map.entry("Новый пароль должен отличаться от текущего", "The new password must differ from the current one"),
            Map.entry("Пароль должен содержать буквы и цифры", "The password must contain letters and digits"),
            Map.entry("укажите логин", "enter a username"),
            Map.entry("укажите пароль", "enter a password"),
            Map.entry("укажите название", "enter a name"),
            Map.entry("неккоректный e-mail", "invalid email address"),
            Map.entry("Запись с такими данными уже существует", "A record with this data already exists"),
            Map.entry("некорректный e-mail", "invalid email address"));

    private static final Map<String, String> ENGLISH_PREFIXES = Map.ofEntries(
            Map.entry("auth-service недоступен: ", "auth-service is unavailable: "),
            Map.entry("Группа уже существует: ", "A group already exists: "),
            Map.entry("Группа не найдена: ", "Group not found: "),
            Map.entry("Неизвестные коды прав: ", "Unknown permission codes: "),
            Map.entry("Неизвестная настройка: ", "Unknown setting: "),
            Map.entry("Некорректное значение настройки ", "Invalid setting value "),
            Map.entry("Значение настройки не может быть null: ", "A setting value cannot be null: "),
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
