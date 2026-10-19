package ru.corvex.mailsmtp.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.MessageHandlerFactory;
import org.subethamail.smtp.RejectException;
import ru.corvex.mailsmtp.outbound.OutboundEvents;
import ru.corvex.mailsmtp.outbound.StorageRawClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Submission 587: только аутентифицированные пользователи, MAIL FROM обязан совпадать с ящиком,
 * права internal/external/bulk проверяются по admin-service. Локальные получатели идут в inbound
 * конвейер, внешние — в outbound очередь. Sent-копию клиент сохраняет через IMAP APPEND на Stage 3,
 * как это делают обычные почтовые клиенты.
 */
@Component
public class SubmissionMessageHandlerFactory implements MessageHandlerFactory {

    private static final Logger log = LoggerFactory.getLogger(SubmissionMessageHandlerFactory.class);
    private static final int BULK_THRESHOLD = 25;
    private static final int MAX_RECIPIENTS = 100;

    private final SubmissionIdentityClient identities;
    private final InboundMessageHandlerFactory.RecipientPolicy localRecipients;
    private final InboundMessageHandlerFactory.RawMessageStoreClient storage;
    private final StorageRawClient rawClient;
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final Set<String> localDomains;

    public SubmissionMessageHandlerFactory(SubmissionIdentityClient identities,
                                           InboundMessageHandlerFactory.RecipientPolicy localRecipients,
                                           InboundMessageHandlerFactory.RawMessageStoreClient storage,
                                           StorageRawClient rawClient,
                                           RabbitTemplate rabbit,
                                           ObjectMapper mapper,
                                           @Value("${corvex.mail.domains:company.local}") String domainsCsv) {
        this.identities = identities;
        this.localRecipients = localRecipients;
        this.storage = storage;
        this.rawClient = rawClient;
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.localDomains = Stream.of(domainsCsv.split(","))
                .map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    @Override
    public MessageHandler create(MessageContext ctx) {
        return new MessageHandler() {
            private String mailFrom;
            private final List<String> recipients = new ArrayList<>();

            @Override
            public void from(String from) throws RejectException {
                SubmissionIdentityClient.AuthenticatedMailbox mailbox = mailbox().orElseThrow(
                        () -> new RejectException(530, "Authentication required"));
                String normalized = SmtpServerConfig.normalizeAddress(from);
                if (!normalized.equalsIgnoreCase(mailbox.email())) {
                    throw new RejectException(553, "Sender address rejected: not owned by user");
                }
                this.mailFrom = normalized;
            }

            @Override
            public void recipient(String recipient) throws RejectException {
                SubmissionIdentityClient.AuthenticatedMailbox mailbox = mailbox().orElseThrow(
                        () -> new RejectException(530, "Authentication required"));
                String normalized = SmtpServerConfig.normalizeAddress(recipient);
                if (recipients.contains(normalized)) {
                    return;
                }
                if (recipients.size() >= MAX_RECIPIENTS
                        || (recipients.size() >= BULK_THRESHOLD && !mailbox.can("MAIL_BULK_SEND"))) {
                    throw new RejectException(552, "Too many recipients");
                }
                if (isLocal(normalized)) {
                    if (!mailbox.can("MAIL_SEND_INTERNAL")) {
                        throw new RejectException(550, "Relay denied");
                    }
                    switch (localRecipients.check(normalized)) {
                        case ACCEPT -> recipients.add(normalized);
                        case TEMP_FAIL -> throw new RejectException(451, "Temporary local problem - please try later");
                        default -> throw new RejectException(550, "No such user here");
                    }
                } else {
                    if (!mailbox.can("MAIL_SEND_EXTERNAL")) {
                        throw new RejectException(550, "Relay denied");
                    }
                    recipients.add(normalized);
                }
            }

            @Override
            public void data(InputStream data) throws IOException {
                SubmissionIdentityClient.AuthenticatedMailbox mailbox = mailbox().orElseThrow(
                        () -> new RejectException(530, "Authentication required"));
                if (mailFrom == null || recipients.isEmpty()) {
                    throw new RejectException(554, "No valid recipients");
                }
                List<String> local = recipients.stream().filter(r -> isLocal(r)).toList();
                List<String> external = recipients.stream().filter(r -> !isLocal(r)).toList();

                InboundMessageHandlerFactory.RawMessageStoreClient.StoredRaw first;
                try {
                    first = storage.storeRaw(data);
                } catch (InboundMessageHandlerFactory.RawMessageStoreClient.MessageTooLargeException e) {
                    throw new RejectException(552, "Message exceeds maximum size");
                } catch (IOException e) {
                    throw new RejectException(451, "Temporary local problem - please try later");
                }

                String localRef = null;
                String outboundRef = null;
                long outboundSize = first.sizeBytes();
                String outboundSha = first.sha256();
                try {
                    if (!local.isEmpty() && !external.isEmpty()) {
                        localRef = first.rawRef();
                        byte[] bytes = rawClient.download(first.rawRef());
                        var second = storage.storeRaw(new ByteArrayInputStream(bytes));
                        outboundRef = second.rawRef();
                        outboundSize = second.sizeBytes();
                        outboundSha = second.sha256();
                    } else if (!local.isEmpty()) {
                        localRef = first.rawRef();
                    } else {
                        outboundRef = first.rawRef();
                    }

                    Instant now = Instant.now();
                    if (localRef != null) {
                        publishConfirmed(InboundMessageHandlerFactory.ROUTING_RECEIVED,
                                new InboundMessageHandlerFactory.InboundReceivedEvent(
                                        localRef, mailFrom, local, String.valueOf(ctx.getRemoteAddress()),
                                        first.sizeBytes(), first.sha256(), now), localRef);
                    }
                    if (outboundRef != null) {
                        publishConfirmed("mail.outbound.queued", new OutboundEvents.OutboundQueuedEvent(
                                outboundRef, mailFrom, external, null, mailbox.userId(), now, 1), outboundRef);
                    }
                    log.info("Submission accepted: from={} local={} external={} size={}",
                            mailFrom, local.size(), external.size(), outboundSize);
                } catch (IOException e) {
                    if (localRef != null) {
                        storage.deleteRaw(localRef);
                    }
                    if (outboundRef != null && !outboundRef.equals(localRef)) {
                        storage.deleteRaw(outboundRef);
                    }
                    throw new RejectException(451, "Temporary local problem - please try later");
                }
            }

            @Override
            public void done() {
                recipients.clear();
                mailFrom = null;
            }

            private Optional<SubmissionIdentityClient.AuthenticatedMailbox> mailbox() {
                if (ctx.getAuthenticationHandler() == null || ctx.getAuthenticationHandler().getIdentity() == null) {
                    return Optional.empty();
                }
                return identities.lookupCached(String.valueOf(ctx.getAuthenticationHandler().getIdentity()));
            }
        };
    }

    private void publishConfirmed(String routingKey, Object event, String correlationId) throws IOException {
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IOException("Не удалось сериализовать событие submission", e);
        }
        CorrelationData correlation = new CorrelationData(correlationId + ":" + routingKey);
        rabbit.convertAndSend(InboundMessageHandlerFactory.EXCHANGE, routingKey, json, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new IOException("RabbitMQ nack для " + correlationId + ": " + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                throw new IOException("RabbitMQ вернул немаршрутизируемое событие " + correlationId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Ожидание publisher confirm прервано", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("RabbitMQ не подтвердил публикацию " + correlationId, e);
        }
    }

    private boolean isLocal(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at > 0 && localDomains.contains(email.substring(at + 1).toLowerCase(Locale.ROOT));
    }
}
