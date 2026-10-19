package ru.corvex.auth.security;

/**
 * Базовая политика паролей этапа 1 (расширяемые политики админки — этап 5):
 * длина 10–128, минимум одна буква и одна цифра. Проверка возвращает текст ошибки или null.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    public static String validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "Пароль должен быть не короче " + MIN_LENGTH + " символов";
        }
        if (password.length() > MAX_LENGTH) {
            return "Пароль должен быть не длиннее " + MAX_LENGTH + " символов";
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            return "Пароль должен содержать буквы и цифры";
        }
        return null;
    }
}
