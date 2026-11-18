package ru.corvex.mailspam.pipeline;

import java.io.IOException;

/**
 * Соединение с mail-storage-service не установилось: сервис перезапускается, а не отвергает письмо.
 * Такие сбои не отправляются в DLQ — сообщение возвращается в очередь и будет обработано,
 * когда хранилище поднимется. Ответ 404/401 остаётся обычным {@link IOException}: повтор его не исправит.
 */
public class StorageUnavailableException extends IOException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
