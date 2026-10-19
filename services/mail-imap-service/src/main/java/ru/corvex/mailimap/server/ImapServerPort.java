package ru.corvex.mailimap.server;

/**
 * Порт почтового протокола IMAP (Ports &amp; Adapters). Бизнес-код Corvex (папки, флаги,
 * квоты, права) работает только с этим интерфейсом и с mail-storage-service, а вся
 * протокольная машина RFC 9051 (IMAP4rev2)/3501 — внутри адаптера на базе
 * <b>Apache James IMAP protocol library</b> (профиль сборки {@code james}, см. README сервиса).
 * <p>Причины именно такого разреза:
 * <ul>
 *   <li>писать и сертифицировать собственный IMAP-парсер (IDLE, UIDPLUS, CONDSTORE,
 *       литералы, кодировки UTF-7 в именах папок) — месяцы работы и источник дыр;</li>
 *   <li>адаптер заменяется (James → иной движок) без трогания хранилища и API веб-клиента;</li>
 *   <li>в unit-тестах поднимается фейковый порт, протокол тестируется отдельно GreenMail/клиентами.</li>
 * </ul>
 * Обязательные расширения для продакшена: IDLE (push в Outlook/Thunderbird), UIDPLUS,
 * MOVE, QUOTA, SPECIAL-USE, STARTTLS на 143 и implicit TLS на 993, SASL PLAIN только внутри TLS.
 */
public interface ImapServerPort {

    /** Старт слушателей 143 (STARTTLS) и 993 (TLS). Ключи — из тома infra/certs (общие с nginx). */
    void start();

    void stop();

    /** Уведомление движка о новом письме: активные IDLE-сессии получают EXISTS без поллинга. */
    void notifyNewMail(String mailbox, String folder);
}
