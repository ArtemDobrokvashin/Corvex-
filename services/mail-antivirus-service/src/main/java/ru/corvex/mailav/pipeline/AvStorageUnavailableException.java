package ru.corvex.mailav.pipeline;

import java.io.IOException;

/**
 * mail-storage-service недоступен (перезапуск, обрыв keep-alive): письмо не проверено и
 * событие ещё не опубликовано. Повтор не портит доставку, поэтому сообщение возвращается
 * в очередь. Ответ 404 остаётся обычным {@link IOException} — повтор его не исправит.
 */
final class AvStorageUnavailableException extends IOException {
    AvStorageUnavailableException(String rawRef, Throwable cause) {
        super("mail-storage недоступен, сканирование отложено: " + rawRef, cause);
    }
}
