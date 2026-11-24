package ru.corvex.mailstorage.settings;

/**
 * Серверные настройки почты. Ровно те поля, которые уже есть в интерфейсе;
 * ничего «на будущее», что пришлось бы поддерживать пустым.
 *
 * @param density           comfortable | compact
 * @param showPreview       показывать превью в списке
 * @param conversations     собирать письма в цепочки
 * @param markRead          automatic | manual
 * @param defaultSort       date-desc | date-asc | sender-asc | subject-asc | unread-first
 * @param signature         текст подписи (до 5000 символов)
 * @param signatureForNew   добавлять в новые письма
 * @param signatureForReplies добавлять в ответы и пересылки
 * @param notifications     браузерные уведомления (сам браузер решает, показывать ли их)
 */
public record MailSettings(
        String density,
        boolean showPreview,
        boolean conversations,
        String markRead,
        String defaultSort,
        String signature,
        boolean signatureForNew,
        boolean signatureForReplies,
        boolean notifications,
        /** Подпись в письмах из Thunderbird/Outlook, а не только из webmail. */
        boolean signatureForSubmission) {

    public static final int MAX_SIGNATURE = 5000;

    /** Значения по умолчанию совпадают с фронтендом: иначе первый вход менял бы вид списка. */
    public static MailSettings defaults() {
        return new MailSettings("comfortable", true, true, "automatic", "date-desc", "", true, false, false, false);
    }
}
