package ru.corvex.mailstorage.mime;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;
import org.springframework.stereotype.Component;
import ru.corvex.mailstorage.api.dto.MailDtos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/** Собирает исходящий MIME для webmail-отправки. BCC сознательно не пишется в заголовки. */
@Component
public class OutgoingMessageFactory {

    public record ComposedMessage(byte[] eml, String messageIdHeader) {
    }

    /**
     * Добавляет серверную подпись к обычному тексту письма.
     *
     * <p>Нужна письмам, отправленным не из webmail (SMTP submission): там текст
     * собирает чужой клиент, и подписи из настроек Corvex в нём нет. Правила
     * простые и предсказуемые: пустая подпись ничего не меняет, уже
     * присутствующая подпись не дублируется, а разделитель — одна пустая
     * строка (RFC 3676 предлагает `-- `, но чужие клиенты на него не смотрят).
     */
    public static String withSignature(String body, String signature) {
        if (body == null) body = "";
        if (signature == null || signature.isBlank()) return body;
        String trimmed = signature.strip();
        if (body.contains(trimmed)) return body;
        return body.isBlank() ? trimmed : body.stripTrailing() + "\n\n" + trimmed;
    }

    public static String sanitizeMessageId(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        // Заголовки ограничены ASCII и не содержат управляющих символов.
        if (!trimmed.matches("[\\x20-\\x7E]{1,998}")) return null;
        return trimmed;
    }

    /**
     * Собирает значение `References`: существующая цепочка + родитель, без
     * дублей и без превышения разумной длины (998 символов на заголовок).
     */
    public static String buildReferences(String existingReferences, String parentMessageId) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (existingReferences != null) {
            for (String part : existingReferences.trim().split("\\s+")) {
                String clean = sanitizeMessageId(part);
                if (clean != null) ids.add(clean);
            }
        }
        String parent = sanitizeMessageId(parentMessageId);
        if (parent != null) ids.add(parent);
        if (ids.isEmpty()) return null;
        StringBuilder value = new StringBuilder();
        for (String id : ids) {
            // Если очередной идентификатор не влезает, цепочку обрываем: лучше
            // короткая корректная, чем заголовок, который почтовик отвергнет.
            if (value.length() + id.length() + 1 > 990) break;
            if (value.length() > 0) value.append(' ');
            value.append(id);
        }
        return value.length() == 0 ? null : value.toString();
    }

    public ComposedMessage compose(String senderEmail, String senderName, String messageIdHeader,
                                   List<String> to, List<String> cc, MailDtos.SendMessageRequest req)
            throws MessagingException, IOException {
        return compose(senderEmail, senderName, messageIdHeader, to, cc, req, null, null);
    }

    /**
     * Собирает письмо с заголовками ветки. `inReplyTo` — `Message-ID`
     * непосредственного родителя, `references` — уже накопленная цепочка;
     * оба значения санируются, потому что перевод строки в заголовке — это
     * инъекция в MIME.
     */
    public ComposedMessage compose(String senderEmail, String senderName, String messageIdHeader,
                                   List<String> to, List<String> cc, MailDtos.SendMessageRequest req,
                                   String inReplyTo, String references)
            throws MessagingException, IOException {
        Properties props = new Properties();
        props.put("mail.mime.charset", StandardCharsets.UTF_8.name());
        MimeMessage message = new MimeMessage(Session.getInstance(props));
        message.setFrom(new InternetAddress(senderEmail, senderName == null ? "" : senderName, StandardCharsets.UTF_8.name()));
        if (!to.isEmpty()) {
            message.setRecipients(Message.RecipientType.TO, addresses(to));
        }
        if (!cc.isEmpty()) {
            message.setRecipients(Message.RecipientType.CC, addresses(cc));
        }
        message.setSubject(req.subject() == null ? "" : req.subject(), StandardCharsets.UTF_8.name());
        message.setSentDate(new Date());
        message.setHeader("Message-ID", messageIdHeader);
        message.setHeader("X-Mailer", "Corvex");
        String safeInReplyTo = sanitizeMessageId(inReplyTo);
        if (safeInReplyTo != null) {
            message.setHeader("In-Reply-To", safeInReplyTo);
        }
        String safeReferences = buildReferences(references, safeInReplyTo);
        if (safeReferences != null) {
            message.setHeader("References", safeReferences);
        }

        MimeBodyPart bodyPart = new MimeBodyPart();
        String text = req.bodyText() == null ? "" : req.bodyText();
        String html = req.bodyHtml();
        if (html != null && !html.isBlank()) {
            MimeMultipart alternative = new MimeMultipart("alternative");
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(text, StandardCharsets.UTF_8.name());
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(html, "text/html; charset=utf-8");
            alternative.addBodyPart(textPart);
            alternative.addBodyPart(htmlPart);
            bodyPart.setContent(alternative);
        } else {
            bodyPart.setText(text, StandardCharsets.UTF_8.name());
        }

        List<MailDtos.SendAttachment> attachments = req.attachments() == null ? List.of() : req.attachments();
        if (attachments.isEmpty()) {
            if (html != null && !html.isBlank()) {
                message.setContent((MimeMultipart) bodyPart.getContent());
            } else {
                message.setText(text, StandardCharsets.UTF_8.name());
            }
        } else {
            MimeMultipart mixed = new MimeMultipart("mixed");
            mixed.addBodyPart(bodyPart);
            for (MailDtos.SendAttachment a : attachments) {
                byte[] bytes = Base64.getDecoder().decode(a.contentBase64() == null ? "" : a.contentBase64());
                MimeBodyPart part = new MimeBodyPart();
                String contentType = (a.contentType() == null || a.contentType().isBlank())
                        ? "application/octet-stream" : a.contentType();
                part.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, contentType)));
                String fileName = (a.fileName() == null || a.fileName().isBlank()) ? "attachment" : a.fileName();
                part.setFileName(MimeUtility.encodeText(fileName, StandardCharsets.UTF_8.name(), "B"));
                mixed.addBodyPart(part);
            }
            message.setContent(mixed);
        }

        message.saveChanges();
        // saveChanges не должен заменять наш стабильный Message-ID: он нужен для DSN и трассировки очереди.
        message.setHeader("Message-ID", messageIdHeader);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            message.writeTo(out);
            return new ComposedMessage(out.toByteArray(), messageIdHeader);
        }
    }

    private InternetAddress[] addresses(List<String> values) throws MessagingException {
        InternetAddress[] out = new InternetAddress[values.size()];
        for (int i = 0; i < values.size(); i++) {
            InternetAddress address = new InternetAddress(values.get(i), true);
            address.validate();
            out[i] = address;
        }
        return out;
    }
}
