package ru.corvex.mailsmtp.outbound;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Прямая доставка на MX получателя через Jakarta Mail (порт 25, STARTTLS если предлагается).
 * Собственный SMTP-клиентский протокол не пишется — это работа библиотеки; здесь только
 * классификация исходов по получателям: SENT / TEMPORARY (4xx, сеть) / PERMANENT (5xx).
 */
@Component
public class SmtpRelayClient {

    private static final Logger log = LoggerFactory.getLogger(SmtpRelayClient.class);
    private static final Pattern SMTP_CODE = Pattern.compile("(?:^|\\D)([245]\\d\\d)(?:\\D|$)");

    private final MxResolver mx;
    private final String heloHostname;
    private final boolean requireStarttls;

    public SmtpRelayClient(MxResolver mx,
                           @Value("${corvex.smtp.hostname:${CORVEX_DOMAIN:mail.company.local}}") String heloHostname,
                           @Value("${corvex.outbound.require-starttls:${CORVEX_SMTP_REQUIRE_STARTTLS:false}}") boolean requireStarttls) {
        this.mx = mx;
        this.heloHostname = heloHostname;
        this.requireStarttls = requireStarttls;
    }

    public enum Status {SENT, TEMPORARY, PERMANENT}

    public record RecipientResult(String recipient, Status status, String diagnostic, String remoteHost) {
    }

    public List<RecipientResult> sendToDomain(byte[] eml, String mailFrom, String domain, List<String> recipients) {
        Map<String, RecipientResult> results = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>(recipients);
        String lastDiagnostic = "MX недоступен";
        for (String host : mx.lookupMxHosts(domain)) {
            if (pending.isEmpty()) {
                break;
            }
            List<RecipientResult> attempt = sendViaHost(eml, mailFrom, host, pending);
            pending = new ArrayList<>();
            for (RecipientResult r : attempt) {
                if (r.status() == Status.TEMPORARY) {
                    pending.add(r.recipient());
                    lastDiagnostic = r.diagnostic();
                } else {
                    results.put(r.recipient(), r);
                }
            }
        }
        for (String recipient : pending) {
            results.put(recipient, new RecipientResult(recipient, Status.TEMPORARY, lastDiagnostic, null));
        }
        return new ArrayList<>(results.values());
    }

    private List<RecipientResult> sendViaHost(byte[] eml, String mailFrom, String host, List<String> recipients) {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", "25");
        props.put("mail.smtp.auth", "false");
        props.put("mail.smtp.localhost", heloHostname);
        props.put("mail.smtp.from", mailFrom);
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "30000");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", Boolean.toString(requireStarttls));
        props.put("mail.smtp.ssl.checkserveridentity", "true");

        Session session = Session.getInstance(props);
        MimeMessage message;
        try {
            message = new MimeMessage(session, new ByteArrayInputStream(eml));
        } catch (MessagingException e) {
            return recipients.stream()
                    .map(r -> new RecipientResult(r, Status.PERMANENT, "Некорректный MIME: " + e.getMessage(), host))
                    .toList();
        }
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect();
            Address[] addresses = recipients.stream().map(r -> {
                try {
                    return (Address) new InternetAddress(r, true);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toArray(Address[]::new);
            transport.sendMessage(message, addresses);
            return recipients.stream()
                    .map(r -> new RecipientResult(r, Status.SENT, "250 OK", host))
                    .toList();
        } catch (SendFailedException e) {
            return classifySendFailed(e, recipients, host);
        } catch (MessagingException | RuntimeException e) {
            int code = smtpCode(e);
            Status status = code >= 500 ? Status.PERMANENT : Status.TEMPORARY;
            log.warn("SMTP доставка через {} не удалась ({}) : {}", host, status, rootMessage(e));
            return recipients.stream()
                    .map(r -> new RecipientResult(r, status, rootMessage(e), host))
                    .toList();
        }
    }

    private List<RecipientResult> classifySendFailed(SendFailedException e, List<String> recipients, String host) {
        Map<String, RecipientResult> out = new LinkedHashMap<>();
        putAll(out, e.getValidSentAddresses(), Status.SENT, "250 OK", host);
        int code = smtpCode(e);
        Status invalidStatus = code >= 500 || code == 0 ? Status.PERMANENT : Status.TEMPORARY;
        putAll(out, e.getInvalidAddresses(), invalidStatus, rootMessage(e), host);
        putAll(out, e.getValidUnsentAddresses(), Status.TEMPORARY, rootMessage(e), host);
        for (String r : recipients) {
            out.putIfAbsent(r, new RecipientResult(r, Status.TEMPORARY, rootMessage(e), host));
        }
        return new ArrayList<>(out.values());
    }

    private void putAll(Map<String, RecipientResult> out, Address[] addresses, Status status, String diagnostic, String host) {
        if (addresses == null) {
            return;
        }
        for (Address a : addresses) {
            String email = a instanceof InternetAddress ia ? ia.getAddress() : a.toString();
            out.put(email, new RecipientResult(email, status, diagnostic, host));
        }
    }

    static int smtpCode(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String message = cur.getMessage();
            if (message != null) {
                Matcher m = SMTP_CODE.matcher(message);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
            if (cur instanceof MessagingException me && me.getNextException() != null) {
                int nested = smtpCode(me.getNextException());
                if (nested > 0) {
                    return nested;
                }
            }
        }
        return 0;
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return message == null || message.isBlank() ? cur.getClass().getSimpleName() : message;
    }
}
