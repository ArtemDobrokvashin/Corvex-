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

    public ComposedMessage compose(String senderEmail, String senderName, String messageIdHeader,
                                   List<String> to, List<String> cc, MailDtos.SendMessageRequest req)
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
