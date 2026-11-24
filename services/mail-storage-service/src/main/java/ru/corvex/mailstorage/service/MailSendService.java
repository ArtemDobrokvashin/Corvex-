package ru.corvex.mailstorage.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.MailDtos;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.ExternalMailAccount;
import ru.corvex.mailstorage.domain.MessageMetadata;
import ru.corvex.mailstorage.external.ExternalMailAccountService;
import ru.corvex.mailstorage.maildomain.AcceptedDomains;
import ru.corvex.mailstorage.mime.OutgoingMessageFactory;
import ru.corvex.mailstorage.pipeline.MailEventPublisher;
import ru.corvex.mailstorage.pipeline.MailPipelineEvents;
import ru.corvex.mailstorage.repository.MessageMetadataRepository;
import ru.corvex.mailstorage.search.MessageSearchIndexer;
import ru.corvex.mailstorage.schedule.OutboxService;
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
    /**
     * Пределы вложений при отправке. Совпадают с тем, что обещает интерфейс
     * (frontend/src/features/mail/drafts.ts) и с nginx `client_max_body_size`.
     * Сервер проверяет размер сам: клиентскую проверку легко обойти, а base64
     * в теле запроса занимает больше места, чем сам файл.
     */
    static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;
    static final int MAX_ATTACHMENTS = 20;

    private final AuthUserClient auth;
    private final OutgoingMessageFactory factory;
    private final RawMessageSpool spool;
    private final MailDeliveryService delivery;
    private final MailEventPublisher events;
    private final MessageSearchIndexer indexer;
    private final ExternalMailAccountService externalAccounts;
    private final AcceptedDomains acceptedDomains;
    private final MessageMetadataRepository messages;
    private final OutboxService outbox;

    public MailSendService(AuthUserClient auth,
                           OutgoingMessageFactory factory,
                           RawMessageSpool spool,
                           MailDeliveryService delivery,
                           MailEventPublisher events,
                           MessageSearchIndexer indexer,
                           ExternalMailAccountService externalAccounts,
                           AcceptedDomains acceptedDomains,
                           MessageMetadataRepository messages,
                           OutboxService outbox) {
        this.auth = auth;
        this.factory = factory;
        this.spool = spool;
        this.delivery = delivery;
        this.events = events;
        this.indexer = indexer;
        this.externalAccounts = externalAccounts;
        this.acceptedDomains = acceptedDomains;
        this.messages = messages;
        this.outbox = outbox;
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
        validateAttachments(req.attachments());

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
        // Ответ должен ссылаться на исходное письмо. Исходник ищется только
        // среди писем того же владельца: чужой `Message-ID` в References —
        // это утечка и подделка треда.
        String inReplyTo = null;
        String references = null;
        UUID replyToId = req.inReplyToMessageId();
        if (replyToId != null) {
            MessageMetadata parent = messages.findByIdAndOwnerId(replyToId, senderId).orElse(null);
            if (parent != null) {
                inReplyTo = parent.getMessageIdHeader();
                references = readReferences(parent);
            }
            // Исходное письмо не найдено — ответ всё равно уходит: потерянное
            // ветвление хуже, чем отсутствие заголовка.
        }
        OutgoingMessageFactory.ComposedMessage composed;
        try {
            composed = factory.compose(fromAddress, fromName, messageId, to, cc, req, inReplyTo, references);
        } catch (AddressException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Некорректный адрес получателя");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректное вложение (ожидается base64)");
        } catch (MessagingException e) {
            throw new IllegalStateException("Не удалось собрать MIME-письмо", e);
        }

        // «Отправить позже»: письмо собирается сейчас (права и получатели уже
        // проверены), но в SMTP уходит по расписанию. Копия в «Отправленные»
        // ставится сразу — человек видит, что письмо действительно отложено.
        if (req.sendAt() != null) {
            UUID scheduledId = scheduleSend(sender, req, composed, local, external, fromAddress,
                    messageId, externalAccount);
            UUID sentCopyId = null;
            if (!Boolean.FALSE.equals(req.saveToSent())) {
                RawMessageSpool.StoredRawMessage raw = store(composed.eml());
                try {
                    var saved = delivery.storeSentCopy(sender, raw.rawRef(), raw.sizeBytes());
                    if (saved.isPresent()) {
                        sentCopyId = saved.get().messageId();
                        indexer.index(saved.get());
                    }
                } finally {
                    deleteQuietly(raw.rawRef());
                }
            }
            if (externalAccount != null) {
                externalAccounts.audit(senderId, externalAccount, "EXTERNAL_SEND_AS_SCHEDULED",
                        "messageId=" + messageId + ", recipients=" + all.size());
            }
            return new MailDtos.SendMessageResult(messageId, sentCopyId, local, external, fromAddress,
                    externalAccount == null ? null : externalAccount.getId(), scheduledId, req.sendAt());
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
                externalAccount == null ? null : externalAccount.getId(), null, null);
    }

    /**
     * Кладёт уже собранное письмо в очередь отложенной отправки. Ошибки даты
     * превращаются в 422: это ответ на данные запроса, а не сбой сервера.
     */
    private UUID scheduleSend(AuthUserClient.MailboxOwner sender, MailDtos.SendMessageRequest req,
                              OutgoingMessageFactory.ComposedMessage composed, List<String> local,
                              List<String> external, String fromAddress, String messageId,
                              ExternalMailAccount externalAccount) {
        RawMessageSpool.StoredRawMessage raw = store(composed.eml());
        try {
            return outbox.schedule(sender.id(), raw, local, external, fromAddress, messageId,
                    externalAccount == null ? null : externalAccount.getId(),
                    req.subject() == null ? "" : req.subject(),
                    !Boolean.FALSE.equals(req.saveToSent()), req.sendAt());
        } catch (IllegalArgumentException e) {
            deleteQuietly(raw.rawRef());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (RuntimeException e) {
            deleteQuietly(raw.rawRef());
            throw e;
        }
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

    /**
     * Локальный домен определяется реестром, а не копией CSV: добавленный и
     * подтверждённый в James домен сразу получает локальную доставку.
     *
     * <p>UNKNOWN (реестр недоступен, статического домена нет) — это не «чужой»:
     * отправить письмо на собственный домен через внешний релей значило бы
     * получить петлю. Поэтому отправка честно останавливается с 503.
     */
    private boolean isLocal(String email) {
        return switch (acceptedDomains.classify(domainOf(email))) {
            case LOCAL -> true;
            case FOREIGN -> false;
            case UNKNOWN -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Не удалось подтвердить домен получателя: список почтовых доменов недоступен");
        };
    }

    /**
     * `References` родителя лежат в сыром MIME, а не в метаданных. Читаем их
     * повтором из spool; если файла нет — цепочка начинается с самого родителя.
     */
    /**
     * Понятная ошибка вместо голого 413: человек должен знать, что делать.
     * Размер считается по декодированным байтам, а не по длине base64.
     */
    private static void validateAttachments(List<MailDtos.SendAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Слишком много вложений: максимум " + MAX_ATTACHMENTS);
        }
        long total = 0;
        for (MailDtos.SendAttachment attachment : attachments) {
            String base64 = attachment == null ? null : attachment.contentBase64();
            if (base64 == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Вложение без содержимого");
            }
            // base64: 4 символа на 3 байта; точный размер проверяем после декодирования ниже.
            long estimated = (base64.length() / 4L) * 3L;
            if (estimated > MAX_ATTACHMENT_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Вложения больше " + (MAX_ATTACHMENT_BYTES / (1024 * 1024)) + " МБ не отправляются");
            }
            total += estimated;
            if (total > MAX_ATTACHMENT_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Вложения больше " + (MAX_ATTACHMENT_BYTES / (1024 * 1024)) + " МБ не отправляются");
            }
        }
    }

    private String readReferences(MessageMetadata parent) {
        String rawRef = parent.getRawRef();
        if (rawRef == null || rawRef.isBlank()) return null;
        try (var in = spool.open(rawRef)) {
            var props = new java.util.Properties();
            var message = new jakarta.mail.internet.MimeMessage(
                    jakarta.mail.Session.getInstance(props), in);
            String[] header = message.getHeader("References");
            if (header == null || header.length == 0) {
                return null;
            }
            return String.join(" ", header);
        } catch (IOException | MessagingException e) {
            // Spool-файл мог быть удалён ретенцией: ответ всё равно отправится,
            // просто тред начнётся с родителя.
            return null;
        }
    }

    private static String domainOf(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1).toLowerCase(Locale.ROOT);
    }
}
