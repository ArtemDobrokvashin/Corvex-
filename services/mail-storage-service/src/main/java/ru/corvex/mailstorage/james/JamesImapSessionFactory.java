package ru.corvex.mailstorage.james;

import java.io.IOException;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.eclipse.angus.mail.imap.IMAPFolder;

/**
 * Experimental and disconnected. Caller must re-check an ACTIVE owner and load
 * the enabled binding from the trusted registry immediately before calling;
 * neither parameters nor passwords may originate from a browser. Native IMAP is
 * disabled in the laptop transition profile. No credential persistence or
 * provisioning/rotation is implemented. No production wiring.
 */
final class JamesImapSessionFactory {
    private final String host;
    private final int port;
    private final SSLContext dedicatedTrust;

    JamesImapSessionFactory(String configuredHost, int port, SSLContext dedicatedTrust) {
        if (configuredHost == null || configuredHost.length() > 253
                || !configuredHost.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")
                || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid IMAP endpoint");
        this.host = configuredHost;
        this.port = port;
        this.dedicatedTrust = Objects.requireNonNull(dedicatedTrust);
    }

    /**
     * Close immediately after the single append. Credentials are not kept in the
     * binding or returned to the caller. JavaMail necessarily receives a String;
     * it cannot be erased reliably. Dynamic logging reconfiguration remains a
     * risk and must be prohibited while credentials or message data are in use.
     */
    Connected connect(JamesMailboxBinding.ProvisionedMailbox mapping, char[] password, String trustedFolder) throws IOException {
        if (mapping == null || mapping.ownerId() == null || !mapping.active()
                || mapping.jamesLogin() == null || !mapping.jamesLogin().matches("cvx-[0-9a-f]{32}@localhost")
                || mapping.accountId() == null || mapping.accountId().isBlank() || mapping.accountId().length() > 1024
                || password == null || password.length < 32 || password.length > 256
                || trustedFolder == null || trustedFolder.isBlank() || trustedFolder.length() > 1024) {
            throw new IllegalArgumentException("Invalid IMAP connection request");
        }
        for (char c : password) {
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
                throw new IllegalArgumentException("Invalid IMAP credential");
            }
        }
        requireSafeLogging();
        Session session = Session.getInstance(properties());
        session.setDebug(false);
        Store store = null;
        try {
            store = session.getStore("imaps");
            store.connect(host, port, mapping.jamesLogin(), new String(password));
            var folder = store.getFolder(trustedFolder);
            if (!(folder instanceof IMAPFolder imap) || !trustedFolder.equals(imap.getFullName())) {
                throw new IOException("IMAP folder unavailable");
            }
            var transport = new JamesImapAppendTransport(mapping.accountId(), trustedFolder, imap);
            return new Connected(store, transport);
        } catch (MessagingException | RuntimeException | IOException failure) {
            if (store != null) try { store.close(); } catch (MessagingException ignored) { }
            // Never include the provider's server response, endpoint, or secrets.
            throw new IOException("IMAP connection unavailable");
        }
    }

    Properties properties() {
        Properties p = new Properties();
        p.setProperty("mail.debug", "false");
        p.setProperty("mail.debug.auth", "false");
        p.setProperty("mail.debug.auth.username", "false");
        p.setProperty("mail.debug.auth.password", "false");
        p.setProperty("mail.imaps.ssl.enable", "true");
        p.setProperty("mail.imaps.ssl.checkserveridentity", "true");
        p.setProperty("mail.imaps.ssl.protocols", "TLSv1.3 TLSv1.2");
        p.put("mail.imaps.ssl.socketFactory", dedicatedTrust.getSocketFactory());
        p.setProperty("mail.imaps.socketFactory.fallback", "false");
        p.setProperty("mail.imaps.connectiontimeout", "5000");
        p.setProperty("mail.imaps.timeout", "15000");
        p.setProperty("mail.imaps.writetimeout", "15000");
        return p;
    }

    static void requireSafeLogging() throws IOException {
        var loggers = LogManager.getLogManager().getLoggerNames();
        while (loggers.hasMoreElements()) {
            String name = loggers.nextElement();
            if ((name.equals("org.eclipse.angus.mail") || name.startsWith("org.eclipse.angus.mail.")
                    || name.equals("jakarta.mail") || name.startsWith("jakarta.mail.")
                    || name.equals("com.sun.mail") || name.startsWith("com.sun.mail."))) {
                Logger logger = LogManager.getLogManager().getLogger(name);
                if (logger != null && logger.isLoggable(Level.FINE)) throw new IOException("Unsafe IMAP debug logging enabled");
            }
        }
        // Account for inherited logger settings even before provider classes load.
        for (String root : new String[]{"org.eclipse.angus.mail", "jakarta.mail", "com.sun.mail"}) {
            if (Logger.getLogger(root).isLoggable(Level.FINE)) throw new IOException("Unsafe IMAP debug logging enabled");
        }
    }

    static final class Connected implements AutoCloseable {
        private final Store store;
        private final JamesImapAppendTransport transport;
        private Connected(Store store, JamesImapAppendTransport transport) { this.store = store; this.transport = transport; }
        JamesImapAppendTransport transport() { return transport; }
        @Override public void close() throws IOException {
            try { store.close(); } catch (MessagingException failure) { throw new IOException("IMAP close failed"); }
        }
    }
}
