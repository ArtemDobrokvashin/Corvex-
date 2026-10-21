package ru.corvex.mailstorage.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.MailDtos;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.ExternalMailAccount;
import ru.corvex.mailstorage.external.ExternalMailAccountService;
import ru.corvex.mailstorage.mime.OutgoingMessageFactory;
import ru.corvex.mailstorage.pipeline.MailEventPublisher;
import ru.corvex.mailstorage.pipeline.MailPipelineEvents;
import ru.corvex.mailstorage.search.MessageSearchIndexer;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Отправка из webmail: одно MIME-письмо раскладывается на независимые spool-копии —
 * локальную (тот же inbound-конвейер spam→AV→delivery), внешнюю (outbound queue SMTP-воркера)
 * и Sent-копию отправителя. Для внешнего ящика нелокальные получатели уходят отдельной
 * очередью mail.outbound.external.queued: mail-smtp-service отправляет их через authenticated
 * SMTP провайдера, а не direct-to-MX от имени Corvex.
 */
@Service
public class MailSendService {

    private static final int MAX_RECIPIENTS = 100;
    private static final int BULK_THRESHOLD = 25;

    private final AuthUserClient auth;
    private final OutgoingMessageFactory factory;
    private final RawMessageSpool spool;
    private final MailDeliveryService delivery;
    private final MailEventPublisher events;
    private final MessageSearchIndexer indexer;
    private final ExternalMailAccountService externalAccounts;
    private final Set<String> localDomains;

    public MailSendService(AuthUserClient auth,
                           OutgoingMessageFactory factory,
                           RawMessageSpool spool,
                           MailDeliveryService delivery,
                           MailEventPublisher events,
                           MessageSearchIndexer indexer,
                           ExternalMailAccountService externalAccounts,
                           @Value("${corvex.mail.domains:${CORVEX_MAIL_DOMAINS:company.local}}") String domainsCsv) {
        this.auth = auth;
        this.factory = factory;
        this.spool = spool;
        this.delivery = delivery;
        this.events = events;
        this.indexer = indexer;
        this.externalAccounts = externalAccounts;
        this.localDomains = Stream.of(domainsCsv.split(","))
                .map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    public MailDtos.SendMessageResult send(UUID senderId, Set<String> permissions, MailDtos.SendMessageRequest req)
            throws IOException {
        AuthUserClient.MailboxOwner sender = auth.findById(senderId)
                .filter(AuthUserClient.MailboxOwner::active)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Отправитель не найден или неактивен"));

        List<String> to = normalizeAll(req.to());
        List<String> cc = normalizeAll(req.cc());
        List<String> bcc = normalizeAll(req.bcc());
        LinkedHashSet<String> all = new LinkedHashSet<>();
        all.addAll(to);
        all.addAll(cc);
        all.addAll(bcc);
        if (all.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Нужен хотя бы один получатель");
        }
        if (all.size() > MAX_RECIPIENTS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Слишком много получателей");
        }
        List<String> local = all.stream().filter(this::isLocal).toList();
        List<String> external = all.stream().filter(a -> !isLocal(a)).toList();
        if (!local.isEmpty() && !permissions.contains("MAIL_SEND_INTERNAL")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Нет права MAIL_SEND_INTERNAL");
        }
        if (!external.isEmpty() && !permissions.contains("MAIL_SEND_EXTERNAL")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Нет права MAIL_SEND_EXTERNAL");
        }
        if (all.size() > BULK_THRESHOLD && !permissions.contains("MAIL_BULK_SEND")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Для массовой рассылки нужно право MAIL_BULK_SEND");
        }

        ExternalMailAccount externalAccount = null;
        String fromAddress = sender.email();
        String fromName = sender.displayName();
        if (req.externalAccountId() != null) {
            externalAccount = externalAccounts.accessibleAccount(senderId, permissions, req.externalAccountId());
            if (!senderId.equals(externalAccount.getOwnerId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Отправлять от имени внешнего ящика может только его владелец Corvex");
            }
            if (!externalAccount.isEnabled() || !externalAccount.isSendEnabled() || !externalAccount.isSmtpEnabled()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Отправка через выбранный внешний ящик отключена");
            }
            fromAddress = externalAccount.getEmailAddress();
            fromName = externalAccount.getDisplayName() == null || externalAccount.getDisplayName().isBlank()
                    ? sender.displayName() : externalAccount.getDisplayName();
        }

        String senderDomain = domainOf(fromAddress);
        String messageId = "<" + UUID.randomUUID() + "@" + senderDomain + ">";
        OutgoingMessageFactory.ComposedMessage composed;
        try {
            composed = factory.compose(fromAddress, fromName, messageId, to, cc, req);
        } catch (AddressException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Некорректный адрес получателя");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректное вложение (ожидается base64)");
        } catch (MessagingException e) {
            throw new IllegalStateException("Не удалось собрать MIME-письмо", e);
        }

        Instant now = Instant.now();
        if (!local.isEmpty()) {
            RawMessageSpool.StoredRawMessage raw = store(composed.eml());
            try {
                events.publishConfirmed("mail.inbound.received", new MailPipelineEvents.InboundReceivedEvent(
                        raw.rawRef(), fromAddress, local, "webmail", raw.sizeBytes(), raw.sha256(), now), raw.rawRef());
            } catch (RuntimeException e) {
                deleteQuietly(raw.rawRef());
                throw e;
            }
        }
        if (!external.isEmpty()) {
            RawMessageSpool.StoredRawMessage raw = store(composed.eml());
            try {
                if (externalAccount != null) {
                    events.publishConfirmed("mail.outbound.external.queued",
                            new MailPipelineEvents.ExternalOutboundQueuedEvent(
                                    raw.rawRef(), externalAccount.getId(), fromAddress, external, messageId,
                                    sender.id(), now, 1), raw.rawRef());
                } else {
                    events.publishConfirmed("mail.outbound.queued", new MailPipelineEvents.OutboundQueuedEvent(
                            raw.rawRef(), fromAddress, external, messageId, sender.id(), now, 1), raw.rawRef());
                }
            } catch (RuntimeException e) {
                deleteQuietly(raw.rawRef());
                throw e;
            }
        }

        UUID sentMessageId = null;
        if (!Boolean.FALSE.equals(req.saveToSent())) {
            RawMessageSpool.StoredRawMessage raw = store(composed.eml());
            try {
                var saved = delivery.storeSentCopy(sender, raw.rawRef(), raw.sizeBytes());
                if (saved.isPresent()) {
                    sentMessageId = saved.get().messageId();
                    indexer.index(saved.get());
                }
            } finally {
                deleteQuietly(raw.rawRef());
            }
        }
        if (externalAccount != null) {
            externalAccounts.audit(senderId, externalAccount, "EXTERNAL_SEND_AS_QUEUED",
                    "messageId=" + messageId + ", recipients=" + all.size());
        }
        return new MailDtos.SendMessageResult(messageId, sentMessageId, local, external, fromAddress,
                externalAccount == null ? null : externalAccount.getId());
    }

    private RawMessageSpool.StoredRawMessage store(byte[] eml) throws IOException {
        try {
            return spool.store(new ByteArrayInputStream(eml));
        } catch (RawMessageSpool.MessageTooLargeException e) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        }
    }

    private void deleteQuietly(String rawRef) {
        try {
            spool.delete(rawRef);
        } catch (IOException ignored) {
            // Осиротевший spool допустим; ложный успех отправки — нет.
        }
    }

    private List<String> normalizeAll(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String email = MailDeliveryService.normalizeAddress(value);
            try {
                InternetAddress parsed = new InternetAddress(email, true);
                parsed.validate();
            } catch (AddressException e) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Некорректный e-mail: " + value);
            }
            out.add(email);
        }
        return new ArrayList<>(out);
    }

    private boolean isLocal(String email) {
        return localDomains.contains(domainOf(email));
    }

    private static String domainOf(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1).toLowerCase(Locale.ROOT);
    }
}
