package ru.corvex.mailsmtp.outbound;

import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Authenticated submission к SMTP провайдера для send-as + APPEND в Sent провайдера (IMAP).
 * Повторно проверяет endpoint перед подключением: custom host не должен увести воркер в
 * loopback/link-local/private сеть после DNS-rebinding.
 */
@Component
public class ExternalSmtpClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalSmtpClient.class);

    private final boolean allowPrivateNetworks;
    private final boolean allowInsecurePlain;
    private final String heloHostname;

    public ExternalSmtpClient(
            @Value("${corvex.external-mail.allow-private-networks:${CORVEX_EXTERNAL_MAIL_ALLOW_PRIVATE_NETWORKS:false}}") boolean allowPrivateNetworks,
            @Value("${corvex.external-mail.allow-insecure-plain:${CORVEX_EXTERNAL_MAIL_ALLOW_INSECURE_PLAIN:false}}") boolean allowInsecurePlain,
            @Value("${corvex.smtp.hostname:${CORVEX_DOMAIN:mail.company.local}}") String heloHostname) {
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.allowInsecurePlain = allowInsecurePlain;
        this.heloHostname = heloHostname;
    }

    public List<SmtpRelayClient.RecipientResult> send(ExternalAccountConfigClient.TransportConfig config,
                                                        byte[] eml, String mailFrom, List<String> recipients) {
        String host = config.smtpHost();
        try {
            validateEndpoint(host, config.smtpPort(), config.smtpSecurity());
        } catch (IllegalArgumentException e) {
            return all(recipients, SmtpRelayClient.Status.PERMANENT, e.getMessage(), host);
        }
        Session session = Session.getInstance(smtpProperties(config));
        MimeMessage message;
        try {
            message = new MimeMessage(session, new ByteArrayInputStream(eml));
        } catch (MessagingException e) {
            return all(recipients, SmtpRelayClient.Status.PERMANENT, "Некорректный MIME: " + rootMessage(e), host);
        }
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(host, config.smtpPort(), config.effectiveSmtpUsername(), config.secret());
            Address[] addresses = recipients.stream().map(r -> {
                try {
                    return (Address) new InternetAddress(r, true);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toArray(Address[]::new);
            transport.sendMessage(message, addresses);
            return all(recipients, SmtpRelayClient.Status.SENT, "250 OK via provider SMTP", host);
        } catch (SendFailedException e) {
            return classifySendFailed(e, recipients, host);
        } catch (AuthenticationFailedException e) {
            return all(recipients, SmtpRelayClient.Status.PERMANENT,
                    "SMTP провайдера отклонил пароль приложения внешнего ящика", host);
        } catch (MessagingException | RuntimeException e) {
            SmtpRelayClient.Status status = classify(e);
            log.warn("Provider SMTP {} не принял письмо внешнего ящика {} ({}) : {}",
                    host, config.emailAddress(), status, rootMessage(e));
            return all(recipients, status, rootMessage(e), host);
        }
    }

    /** APPEND в Sent провайдера. Ошибка здесь не отменяет уже выполненную SMTP-отправку. */
    public boolean appendSent(ExternalAccountConfigClient.TransportConfig config, byte[] eml) {
        if (!config.saveSentOnServer() || !"IMAP".equalsIgnoreCase(config.incomingProtocol())) {
            return false;
        }
        try {
            validateEndpoint(config.incomingHost(), config.incomingPort(), config.incomingSecurity());
            Session session = Session.getInstance(incomingProperties(config));
            try (Store store = session.getStore(storeProtocol(config.incomingSecurity()))) {
                store.connect(config.incomingHost(), config.incomingPort(), config.incomingUsername(), config.secret());
                Folder sent = findSentFolder(store, config.sentFolderName());
                if (sent == null) return false;
                sent.open(Folder.READ_WRITE);
                try {
                    MimeMessage copy = new MimeMessage(session, new ByteArrayInputStream(eml));
                    copy.setFlag(Flags.Flag.SEEN, true);
                    sent.appendMessages(new Message[]{copy});
                    return true;
                } finally {
                    try {
                        sent.close(false);
                    } catch (MessagingException ignored) {
                        // store close ниже закроет соединение
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Не удалось сохранить Sent-копию на сервере внешнего ящика {}: {}",
                    config.emailAddress(), rootMessage(e));
            return false;
        }
    }

    private Folder findSentFolder(Store store, String preferred) throws MessagingException {
        if (preferred != null && !preferred.isBlank()) {
            Folder folder = store.getFolder(preferred);
            if (folder != null && folder.exists()) return folder;
        }
        Folder[] folders = store.getDefaultFolder().list("*");
        Folder byName = null;
        for (Folder folder : folders) {
            if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
            if (folder instanceof IMAPFolder imap) {
                try {
                    String[] attrs = imap.getAttributes();
                    if (attrs != null) {
                        for (String attr : attrs) {
                            if ("\\Sent".equalsIgnoreCase(attr)) return folder;
                        }
                    }
                } catch (MessagingException ignored) {
                    // fallback по имени ниже
                }
            }
            String last = folder.getFullName().toLowerCase(Locale.ROOT);
            int slash = Math.max(last.lastIndexOf('/'), last.lastIndexOf('.'));
            if (slash >= 0) last = last.substring(slash + 1);
            if (last.equals("sent") || last.equals("sent messages") || last.equals("sent items")
                    || last.equals("отправленные")) {
                byName = folder;
            }
        }
        return byName;
    }

    private Properties smtpProperties(ExternalAccountConfigClient.TransportConfig config) {
        Properties p = new Properties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.host", config.smtpHost());
        p.put("mail.smtp.port", String.valueOf(config.smtpPort()));
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.from", config.emailAddress());
        p.put("mail.smtp.localhost", heloHostname);
        p.put("mail.smtp.connectiontimeout", "15000");
        p.put("mail.smtp.timeout", "60000");
        p.put("mail.smtp.writetimeout", "60000");
        p.put("mail.smtp.ssl.checkserveridentity", "true");
        if ("SSL_TLS".equalsIgnoreCase(config.smtpSecurity())) {
            p.put("mail.smtp.ssl.enable", "true");
        } else if ("STARTTLS".equalsIgnoreCase(config.smtpSecurity())) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        return p;
    }

    private Properties incomingProperties(ExternalAccountConfigClient.TransportConfig config) {
        Properties p = new Properties();
        p.put("mail.store.protocol", storeProtocol(config.incomingSecurity()));
        p.put("mail.imap.host", config.incomingHost());
        p.put("mail.imap.port", String.valueOf(config.incomingPort()));
        p.put("mail.imap.connectiontimeout", "15000");
        p.put("mail.imap.timeout", "60000");
        p.put("mail.imap.ssl.checkserveridentity", "true");
        if ("STARTTLS".equalsIgnoreCase(config.incomingSecurity())) {
            p.put("mail.imap.starttls.enable", "true");
            p.put("mail.imap.starttls.required", "true");
        }
        return p;
    }

    private static String storeProtocol(String security) {
        return "SSL_TLS".equalsIgnoreCase(security) ? "imaps" : "imap";
    }

    private void validateEndpoint(String host, Integer port, String security) {
        if (host == null || host.isBlank() || port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Некорректный SMTP/IMAP endpoint внешнего ящика");
        }
        if ("PLAIN".equalsIgnoreCase(security) && !allowInsecurePlain) {
            throw new IllegalArgumentException("Подключение к внешнему ящику без TLS запрещено политикой сервера");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Не удалось разрешить DNS внешнего почтового сервера: " + host);
        }
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isMulticastAddress() || (!allowPrivateNetworks && isPrivateOrReserved(address))) {
                throw new IllegalArgumentException("Внешний почтовый сервер указывает на запрещённый сетевой адрес");
            }
        }
    }

    private boolean isPrivateOrReserved(InetAddress address) {
        if (address.isSiteLocalAddress()) return true;
        if (address instanceof Inet4Address v4) {
            byte[] b = v4.getAddress();
            int a = Byte.toUnsignedInt(b[0]);
            int c = Byte.toUnsignedInt(b[1]);
            return a == 0 || (a == 100 && c >= 64 && c <= 127) || a >= 240;
        }
        byte[] b = address.getAddress();
        return b.length == 16 && ((b[0] & (byte) 0xfe) == (byte) 0xfc);
    }

    private List<SmtpRelayClient.RecipientResult> classifySendFailed(SendFailedException e,
                                                                      List<String> recipients, String host) {
        Map<String, SmtpRelayClient.RecipientResult> out = new LinkedHashMap<>();
        putAll(out, e.getValidSentAddresses(), SmtpRelayClient.Status.SENT, "250 OK via provider SMTP", host);
        int code = SmtpRelayClient.smtpCode(e);
        SmtpRelayClient.Status invalid = code >= 500 || code == 0
                ? SmtpRelayClient.Status.PERMANENT : SmtpRelayClient.Status.TEMPORARY;
        putAll(out, e.getInvalidAddresses(), invalid, rootMessage(e), host);
        putAll(out, e.getValidUnsentAddresses(), SmtpRelayClient.Status.TEMPORARY, rootMessage(e), host);
        for (String recipient : recipients) {
            out.putIfAbsent(recipient, new SmtpRelayClient.RecipientResult(
                    recipient, SmtpRelayClient.Status.TEMPORARY, rootMessage(e), host));
        }
        return new ArrayList<>(out.values());
    }

    private void putAll(Map<String, SmtpRelayClient.RecipientResult> out, Address[] addresses,
                        SmtpRelayClient.Status status, String diagnostic, String host) {
        if (addresses == null) return;
        for (Address a : addresses) {
            String email = a instanceof InternetAddress ia ? ia.getAddress() : a.toString();
            out.put(email, new SmtpRelayClient.RecipientResult(email, status, diagnostic, host));
        }
    }

    private List<SmtpRelayClient.RecipientResult> all(List<String> recipients, SmtpRelayClient.Status status,
                                                       String diagnostic, String host) {
        return recipients.stream()
                .map(r -> new SmtpRelayClient.RecipientResult(r, status, diagnostic, host))
                .toList();
    }

    private SmtpRelayClient.Status classify(Throwable e) {
        Throwable root = root(e);
        if (root instanceof SSLException || root instanceof AuthenticationFailedException) {
            return SmtpRelayClient.Status.PERMANENT;
        }
        if (root instanceof UnknownHostException || root instanceof ConnectException || root instanceof SocketTimeoutException) {
            return SmtpRelayClient.Status.TEMPORARY;
        }
        int code = SmtpRelayClient.smtpCode(e);
        return code >= 500 ? SmtpRelayClient.Status.PERMANENT : SmtpRelayClient.Status.TEMPORARY;
    }

    private Throwable root(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        if (current instanceof MessagingException messaging && messaging.getNextException() != null) {
            return messaging.getNextException();
        }
        return current;
    }

    private String rootMessage(Throwable e) {
        String message = root(e).getMessage();
        String clean = message == null || message.isBlank() ? root(e).getClass().getSimpleName() : message;
        clean = clean.replaceAll("[\\r\\n]+", " ").trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 500);
    }
}
