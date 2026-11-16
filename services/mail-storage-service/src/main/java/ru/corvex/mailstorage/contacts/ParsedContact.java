package ru.corvex.mailstorage.contacts;

/**
 * Контакт, извлечённый из файла импорта. Поля ещё не нормализованы и не проверены:
 * это делает ContactService, чтобы правила были одинаковыми для API и импорта.
 *
 * @param line номер строки (vCard) или записи (CSV) в исходном файле — для отчёта об ошибках
 */
public record ParsedContact(
        String displayName,
        String email,
        String phone,
        String organization,
        String jobTitle,
        String note,
        String groupName,
        int line) {
}
