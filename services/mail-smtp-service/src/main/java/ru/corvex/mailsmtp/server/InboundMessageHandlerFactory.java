package ru.corvex.mailsmtp.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.MessageHandlerFactory;
import org.subethamail.smtp.RejectException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Приём входящей почты через SubEtha SMTP (RFC 5321 берёт на себя библиотека — собственные
 * TCP/протокольные парсеры не пишутся, см. ADR-03).
 *
 * <p>Stage-2 инвариант против потери почты: DATA потоково пишется в durable spool
 * mail-storage-service, затем событие публикуется в RabbitMQ с ожиданием publisher confirm.
 * Только после обоих шагов SubEtha отвечает отправителю 250 OK. Если storage/RabbitMQ
 * недоступны, SMTP отвечает 451, и нормальный внешний сервер сам повторит доставку позже.
 */
@Component
public class InboundMessageHandlerFactory implements MessageHandlerFactory {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageHandlerFactory.class);
    static final String EXCHANGE = "corvex.mail";
    static final String ROUTING_RECEIVED = "mail.inbound.received";

    private final RabbitTemplate rabbit;
    private final RecipientPolicy recipientPolicy;
    private final RawMessageStoreClient storage;
    private final ObjectMapper mapper;

    public InboundMessageHandlerFactory(RabbitTemplate rabbit, RecipientPolicy recipientPolicy,
                                        RawMessageStoreClient storage, ObjectMapper mapper) {
        this.rabbit = rabbit;
        this.recipientPolicy = recipientPolicy;
        this.storage = storage;
        this.mapper = mapper;
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
                switch (recipientPolicy.check(recipient)) {
                    case ACCEPT -> recipients.add(recipient);
                    case REJECT_RELAY -> throw new RejectException(550, "Relay denied");
                    case REJECT_UNKNOWN_USER -> throw new RejectException(550, "No such user here");
                    case TEMP_FAIL -> throw new RejectException(451, "Temporary local problem - please try later");
                }
            }

            @Override
            public String data(InputStream data) throws RejectException, IOException {
                if (recipients.isEmpty()) {
                    throw new RejectException(554, "No valid recipients");
                }
                RawMessageStoreClient.StoredRaw stored;
                try {
                    stored = storage.storeRaw(data);
                } catch (RawMessageStoreClient.MessageTooLargeException e) {
                    throw new RejectException(552, "Message exceeds maximum size");
                } catch (IOException e) {
                    log.warn("mail-storage spool недоступен, SMTP временно отклоняет письмо: {}", e.getMessage());
                    throw new RejectException(451, "Temporary local problem - please try later");
                }

                var event = new InboundReceivedEvent(
                        stored.rawRef(), mailFrom, List.copyOf(recipients),
                        String.valueOf(ctx.getRemoteAddress()), stored.sizeBytes(), stored.sha256(), Instant.now());
                try {
                    publishConfirmed(event);
                } catch (IOException e) {
                    storage.deleteRaw(stored.rawRef());
                    throw new RejectException(451, "Temporary local problem - please try later");
                }
                log.info("Inbound mail accepted: from={} rcpt={} size={} rawRef={}",
                        mailFrom, recipients, stored.sizeBytes(), stored.rawRef());
                return null;
            }

            @Override
            public void done() {
                recipients.clear();
                mailFrom = null;
            }
        };
    }

    private void publishConfirmed(InboundReceivedEvent event) throws IOException {
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IOException("Не удалось сериализовать событие входящей почты", e);
        }
        CorrelationData correlation = new CorrelationData(event.rawRef());
        rabbit.convertAndSend(EXCHANGE, ROUTING_RECEIVED, json, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new IOException("RabbitMQ nack для " + event.rawRef() + ": " + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                throw new IOException("RabbitMQ вернул немаршрутизируемое событие " + event.rawRef());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Ожидание publisher confirm прервано", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("RabbitMQ не подтвердил публикацию " + event.rawRef(), e);
        }
    }

    /** Событие постановки письма в конвейер: spam → antivirus → delivery → index. */
    public record InboundReceivedEvent(String rawRef, String mailFrom, List<String> recipients,
                                       String remoteAddress, long sizeBytes, String sha256,
                                       Instant receivedAt) {
    }

    /** Политика RCPT: только существующие активные ящики локальных доменов. */
    public interface RecipientPolicy {
        enum Decision {ACCEPT, REJECT_RELAY, REJECT_UNKNOWN_USER, TEMP_FAIL}

        Decision check(String address);
    }

    /** Порт durable spool-а сырых писем в mail-storage-service. */
    public interface RawMessageStoreClient {
        record StoredRaw(String rawRef, String sha256, long sizeBytes) {
        }

        class MessageTooLargeException extends IOException {
            public MessageTooLargeException(String message) {
                super(message);
            }
        }

        StoredRaw storeRaw(InputStream eml) throws IOException;

        default void deleteRaw(String rawRef) {
            // Best-effort уборка, если после записи spool-а не удалось подтвердить событие в RabbitMQ.
        }
    }
}
