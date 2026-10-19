package ru.corvex.mailstorage.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Превращает permanent-fail исходящего воркера в DSN письмо отправителю. DSN идёт обычным
 * inbound-конвейером, поэтому попадает в INBOX с теми же spam/AV/квота правилами, что и почта.
 */
@Component
public class OutboundBounceListener {

    private static final Logger log = LoggerFactory.getLogger(OutboundBounceListener.class);
    private static final DateTimeFormatter RFC_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper;
    private final AuthUserClient auth;
    private final RawMessageSpool spool;
    private final MailEventPublisher events;

    public OutboundBounceListener(ObjectMapper mapper, AuthUserClient auth, RawMessageSpool spool, MailEventPublisher events) {
        this.mapper = mapper;
        this.auth = auth;
        this.spool = spool;
        this.events = events;
    }

    @RabbitListener(queues = StoragePipelineAmqpConfig.BOUNCE_QUEUE, containerFactory = "mailListenerFactory")
    public void onBounced(String payload) throws IOException {
        MailPipelineEvents.OutboundBouncedEvent event =
                mapper.readValue(payload, MailPipelineEvents.OutboundBouncedEvent.class);
        String sender = event.mailFrom() == null ? "" : event.mailFrom().trim().toLowerCase(Locale.ROOT);
        if (auth.findByEmail(sender).filter(AuthUserClient.MailboxOwner::active).isEmpty()) {
            log.warn("DSN пропущен: исходный отправитель {} не найден/неактивен", sender);
            return;
        }
        String domain = domainOf(sender);
        String daemon = "MAILER-DAEMON@" + domain;
        String eml = "From: Mail Delivery System <" + daemon + ">\r\n"
                + "To: " + header(sender) + "\r\n"
                + "Subject: Delivery failed: " + header(event.recipient()) + "\r\n"
                + "Date: " + RFC_DATE.format(Instant.now()) + "\r\n"
                + "Message-ID: <" + UUID.randomUUID() + "@" + domain + ">\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n"
                + "Auto-Submitted: auto-generated\r\n"
                + "\r\n"
                + "Your message could not be delivered to " + event.recipient() + ".\r\n\r\n"
                + "Original Message-ID: " + event.messageIdHeader() + "\r\n"
                + "Diagnostic: " + event.diagnostic() + "\r\n";
        RawMessageSpool.StoredRawMessage raw = spool.store(
                new ByteArrayInputStream(eml.getBytes(StandardCharsets.UTF_8)));
        try {
            events.publishConfirmed("mail.inbound.received", new MailPipelineEvents.InboundReceivedEvent(
                    raw.rawRef(), daemon, List.of(sender), "dsn", raw.sizeBytes(), raw.sha256(), Instant.now()), raw.rawRef());
        } catch (RuntimeException e) {
            spool.delete(raw.rawRef());
            throw e;
        }
    }

    private static String header(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
    }

    private static String domainOf(String email) {
        int at = email.lastIndexOf('@');
        return at < 0 ? "localhost" : email.substring(at + 1);
    }
}
