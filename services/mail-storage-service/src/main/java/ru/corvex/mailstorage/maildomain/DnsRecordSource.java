package ru.corvex.mailstorage.maildomain;

import java.util.List;

/**
 * Источник DNS-наблюдений для проверки домена. Реализация обязана отличать
 * «записи нет» (пустой список) от «ответа получить не удалось»
 * ({@link DnsLookupException}): иначе недоступный DNS выглядел бы как
 * доказательство отсутствия записи.
 */
public interface DnsRecordSource {

    /** Обменные узлы MX без завершающей точки, в нижнем регистре. */
    List<String> mxHosts(String domain);

    /** TXT-записи с уже склеенными строками (RFC 7208 для SPF, RFC 6376 для DKIM). */
    List<String> txt(String name);

    List<String> cnameTargets(String name);

    List<String> addresses(String name);

    final class DnsLookupException extends RuntimeException {
        public DnsLookupException(String message) {
            super(message);
        }
    }
}
