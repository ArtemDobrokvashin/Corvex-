package ru.corvex.mailsmtp.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.MessageHandlerFactory;
import org.subethamail.smtp.RejectException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Приём входящей почты через SubEtha SMTP (RFC 5321 берёт на себя библиотека —
 * собственные TCP/протокольные парсеры не пишутся, см. архитектурное решение ADR-03).
 * <p>Ключевой принцип: SMTP-сессия должна завершаться быстро. Здесь выполняются только
 * дешёвые синхронные проверки (наши ли домены у получателей, лимит размера, базовая
 * валидность конверта). Всё дорогое — MIME-разбор, SpamAssassin, ClamAV, доставка в
 * Maildir, индексация — асинхронно, через событие {@code mail.inbound.received} в RabbitMQ.
 * Сырой .eml по дороге пишет mail-storage-service (HTTP internal API), SMTP-сервис ФС не трогает.
 */
@Component
public class InboundMessageHandlerFactory implements MessageHandlerFactory {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageHandlerFactory.class);
    private static final long MAX_MESSAGE_BYTES = 25L * 1024 * 1024; // настраивается админом, значение по умолчанию — 25 МБ

    private final RabbitTemplate rabbit;
    private final LocalDomainPolicy localDomains;
    private final RawMessageStoreClient storage;

    public InboundMessageHandlerFactory(RabbitTemplate rabbit, LocalDomainPolicy localDomains,
                                        RawMessageStoreClient storage) {
        this.rabbit = rabbit;
        this.localDomains = localDomains;
        this.storage = storage;
    }

    @Override
    public MessageHandler create(MessageContext ctx) {
        return new MessageHandler() {
            private String mailFrom;
            private final List<String> recipients = new ArrayList<>();

            @Override
            public void from(String from) throws RejectException {
                this.mailFrom = from;
            }

            @Override
            public void recipient(String recipient) throws RejectException {
                // Анти-open-relay: на 25 порту принимаем ТОЛЬКО для своих доменов.
                // Исходящая отправка своих пользователей — порт 587 с обязательной SMTP AUTH (отдельный listener).
                if (!localDomains.isLocalRecipient(recipient)) {
                    throw new RejectException(550, "Relay denied");
                }
                recipients.add(recipient);
            }

            @Override
            public String data(InputStream data) throws IOException {
                byte[] eml = data.readAllBytes();
                if (eml.length > MAX_MESSAGE_BYTES) {
                    throw new RejectException(552, "Message exceeds maximum size");
                }
                String rawRef = storage.storeRaw(eml); // -> mail-storage-service, возвращает id сырого объекта
                var event = new InboundReceivedEvent(rawRef, mailFrom, List.copyOf(recipients),
                        ctx.getRemoteAddress().toString(), eml.length);
                rabbit.convertAndSend("corvex.mail", "mail.inbound.received", event);
                log.info("Inbound mail accepted: from={} rcpt={} size={} rawRef={}",
                        mailFrom, recipients, eml.length, rawRef);
                return rawRef; // SubEtha вернёт этот id отправителю в ответе 250 — удобно для трассировки
            }

            @Override
            public void done() {
                recipients.clear();
            }
        };
    }

    /** Событие постановки письма в конвейер: spam → antivirus → delivery → index. */
    public record InboundReceivedEvent(String rawRef, String mailFrom, List<String> recipients,
                                       String remoteAddress, long sizeBytes) {
    }

    /** Порт: список локальных доменов (кэш в Redis, источник истины — admin-service). */
    public interface LocalDomainPolicy {
        boolean isLocalRecipient(String address);
    }

    /** Порт: сохранение сырого письма во временное хранилище до окончания проверок. */
    public interface RawMessageStoreClient {
        String storeRaw(byte[] eml);
    }
}
