package ru.corvex.mailstorage.mime;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.corvex.mailstorage.storage.MaildirStorage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * MIME-разбор на Jakarta Mail. Парсер не пытается быть почтовым протоколом: он извлекает из уже
 * сохранённого .eml только метаданные для списков, сниппет/текст для поиска и вложения для CAS.
 */
@Component
public class MimeMailParser {

    private static final Logger log = LoggerFactory.getLogger(MimeMailParser.class);
    private static final int MAX_BODY_CHARS = 200_000;

    private final MaildirStorage maildir;

    public MimeMailParser(MaildirStorage maildir) {
        this.maildir = maildir;
    }

    public record ParsedAddress(String address, String displayName) {
    }

    public record ParsedAttachment(String fileName, String contentType, long sizeBytes, String sha256) {
    }

    public record ParsedMail(
            String messageIdHeader,
            String sender,
            String subject,
            String snippet,
            String bodyText,
            String bodyHtml,
            Instant internalDate,
            List<ParsedAddress> to,
            List<ParsedAddress> cc,
            List<ParsedAttachment> attachments) {
    }

    public ParsedMail parse(InputStream in, boolean storeAttachments) throws IOException, MessagingException {
        Properties props = new Properties();
        props.put("mail.mime.decodefilename", "true");
        MimeMessage message = new MimeMessage(Session.getInstance(props), in);

        String sender = firstAddress(message.getFrom());
        String subject = decode(message.getSubject());
        BodyCollector body = new BodyCollector();
        List<ParsedAttachment> attachments = new ArrayList<>();
        collect(message, body, attachments, storeAttachments);

        String text = truncate(body.text.toString().strip(), MAX_BODY_CHARS);
        String html = truncate(body.html.toString().strip(), MAX_BODY_CHARS);
        if (text.isBlank() && !html.isBlank()) {
            text = htmlToText(html);
        }
        String snippet = truncate(text.replaceAll("\\s+", " ").strip(), 200);
        return new ParsedMail(
                message.getMessageID(),
                sender,
                subject == null ? "" : subject,
                snippet,
                text,
                html.isBlank() ? null : html,
                dateOrNow(message),
                addresses(message.getRecipients(Message.RecipientType.TO)),
                addresses(message.getRecipients(Message.RecipientType.CC)),
                List.copyOf(attachments));
    }

    private void collect(Part part, BodyCollector body, List<ParsedAttachment> attachments,
                         boolean storeAttachments) throws IOException, MessagingException {
        Object content = safeContent(part);
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i), body, attachments, storeAttachments);
            }
            return;
        }
        if (content instanceof Message nested) {
            collect(nested, body, attachments, storeAttachments);
            return;
        }

        String disposition = part.getDisposition();
        String fileName = decode(part.getFileName());
        String contentType = baseContentType(part.getContentType());
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(disposition) || fileName != null;
        if (attachment) {
            String safeName = (fileName == null || fileName.isBlank()) ? "attachment" : fileName;
            if (storeAttachments) {
                try (InputStream ain = part.getInputStream()) {
                    MaildirStorage.StoredAttachment stored = maildir.storeAttachment(ain);
                    attachments.add(new ParsedAttachment(safeName, contentType, stored.sizeBytes(), stored.sha256()));
                }
            } else {
                attachments.add(new ParsedAttachment(safeName, contentType, Math.max(0, part.getSize()), null));
            }
            return;
        }

        if (content instanceof String s) {
            if (contentType.startsWith("text/plain")) {
                body.text.append(s).append('\n');
            } else if (contentType.startsWith("text/html")) {
                body.html.append(s).append('\n');
            }
        }
    }

    private Object safeContent(Part part) throws MessagingException {
        try {
            return part.getContent();
        } catch (IOException e) {
            // Битый charset/transfer-encoding одной части не должен хоронить всё письмо целиком.
            log.warn("Не удалось прочитать MIME-часть ({}): {}", part.getContentType(), e.getMessage());
            return null;
        }
    }

    private static final class BodyCollector {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder html = new StringBuilder();
    }

    private List<ParsedAddress> addresses(Address[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<ParsedAddress> out = new ArrayList<>();
        for (Address a : values) {
            if (a instanceof InternetAddress ia) {
                out.add(new ParsedAddress(ia.getAddress(), decode(ia.getPersonal())));
            } else if (a != null) {
                out.add(new ParsedAddress(decode(a.toString()), null));
            }
        }
        return List.copyOf(out);
    }

    private String firstAddress(Address[] values) {
        List<ParsedAddress> parsed = addresses(values);
        if (parsed.isEmpty()) {
            return null;
        }
        ParsedAddress first = parsed.get(0);
        if (first.displayName() != null && !first.displayName().isBlank()) {
            return first.displayName() + " <" + first.address() + ">";
        }
        return first.address();
    }

    private Instant dateOrNow(MimeMessage message) throws MessagingException {
        Date d = message.getReceivedDate();
        if (d == null) {
            d = message.getSentDate();
        }
        return d == null ? Instant.now() : d.toInstant();
    }

    private static String baseContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        int semi = contentType.indexOf(';');
        String base = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static String decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return MimeUtility.decodeText(value);
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String htmlToText(String html) {
        String text = html
                .replaceAll("(?is)<(script|style).*?>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return truncate(text.replaceAll("\\s+", " ").strip(), MAX_BODY_CHARS);
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
