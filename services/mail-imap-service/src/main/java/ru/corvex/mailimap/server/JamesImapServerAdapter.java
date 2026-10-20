package ru.corvex.mailimap.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Адаптер Apache James IMAP за портом {@link ImapServerPort}.
 *
 * <p>Статус этапа 3 в этой итерации: готов и покрыт тестами весь Corvex-слой, который
 * потребляет движок, — внутренний протокольный API mail-storage-service (папки/UID/флаги/
 * expunge/append/copy/quota), клиенты {@code ImapAuthClient}/{@code ImapStorageClient} и
 * модель {@code ImapFolderPaths}/{@code ImapFlags}. Остаётся механика James: реализация
 * MailboxManager/MessageManager SPI (реактивные интерфейсы mailbox-api 3.8.2, ~70 методов)
 * и bootstrap IMAPServer. Писать её без компилятора в цикле нельзя: сигнатуры James между
 * минорными версиями плавают, и «написанный вслепую» адаптер гарантированно не соберётся.
 * Таблица соответствия методов SPI эндпоинтам хранилища — в README сервиса; это первый
 * шаг следующей итерации на JDK-хосте (mvn compile как обратная связь).</p>
 *
 * <p>Поэтому start() сейчас честно ничего не биндит: порты 143/993 остаются закрытыми,
 * сервис живёт ради actuator/health, а включение CORVEX_IMAP_ENABLED даёт явную ошибку
 * в логе вместо молча неработающего протокола.</p>
 */
@Component
public class JamesImapServerAdapter implements ImapServerPort, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JamesImapServerAdapter.class);

    private final boolean enabled;
    private volatile boolean running;

    public JamesImapServerAdapter(@Value("${corvex.imap.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (!enabled) {
            log.warn("IMAP-сервер выключен (CORVEX_IMAP_ENABLED=false): слушатели 143/993 не поднимаются");
            return;
        }
        // TODO(stage-3, next iteration on JDK host): James MailboxManager/MessageManager over
        // ImapStorageClient + IMAPServer bootstrap. See README mapping table before coding.
        log.error("CORVEX_IMAP_ENABLED=true, но движок Apache James IMAP ещё не подключён в этой сборке: "
                + "порты 143/993 НЕ открыты. Хранилище и модельный слой готовы, остался MailboxManager SPI.");
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void notifyNewMail(String mailbox, String folder) {
        // IDLE-push появится вместе с движком; сейчас фиксируем событие для наблюдаемости.
        log.debug("mail.delivered -> {} / {} (IDLE ещё не активен)", mailbox, folder);
    }
}
