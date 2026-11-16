package ru.corvex.mailstorage.contacts;

/**
 * Строка, которую не удалось перенести. Причина формулируется для пользователя,
 * а не кодом: в интерфейсе она показывается рядом с номером записи.
 */
public record ImportIssue(int line, String value, String reason) {
}
