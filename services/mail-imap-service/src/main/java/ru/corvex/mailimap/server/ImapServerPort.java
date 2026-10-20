package ru.corvex.mailimap.server;

/**
 * Порт почтового протокола IMAP (Ports &amp; Adapters). Бизнес-код Corvex (папки, флаги,
 * квоты, права) работает только с этим интерфейсом и с mail-storage-service, а вся
 * протокольная машина RFC 9051 (IMAP4rev2)/3501 — внутри адаптера на базе
 * <b>Apache James IMAP protocol library 3.8.2</b> (прямые зависимости в pom сервиса).
 * <p>Причины именно такого разреза:
 * <ul>
 *   <li>писать и сертифицировать собственный IMAP-парсер (IDLE, UIDPLUS, CONDSTORE,
 *       литералы, кодировки UTF-7 в именах папок) — месяцы работы и источник дыр;</li>
 *   <li>адаптер заменяется (James → иной движок) без трогания хранилища и API веб-клиента;</li>
 *   <li>в unit-тестах поднимается фейковый порт, протокол тестируется отдельно GreenMail/клиентами.</li>
 * </ul>
 * Обязательные расширения для продакшена: IDLE, UIDPLUS, MOVE, QUOTA, SPECIAL-USE,
 * STARTTLS на 143 и implicit TLS на 993. Требовать TLS до LOGIN на plain-порту можно
 * флагом {@code CORVEX_IMAP_REQUIRE_TLS_FOR_AUTH=true}; по умолчанию он выключен только
 * ради изолированного локального smoke без сертификата.
 */
public interface ImapServerPort {

    /** Старт слушателей 143 (STARTTLS) и 993 (TLS). Ключи — из тома infra/certs (общие с nginx). */
    void start();

    void stop();

    /**
     * Точка уведомления о внешне доставленном письме. В текущей James-конфигурации IDLE
     * питается событиями EventBus от операций этого же IMAP-процесса; межпроцессный push
     * из SMTP/storage требует распределённого EventBus этапа 5 и пока честно не обещается.
     */
    void notifyNewMail(String mailbox, String folder);
}
