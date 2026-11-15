package ru.corvex.mailstorage.james;

import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Configuration tests only; no real server, certificate, or credential flow. */
class JamesImapSessionFactoryTest {
    @Test void configRequiresDedicatedTlsAndNoFallbackOrDebug() throws Exception {
        SSLContext dedicated = SSLContext.getDefault();
        var factory = new JamesImapSessionFactory("james-imap", 993, dedicated);
        var p = factory.properties();
        assertEquals("true", p.getProperty("mail.imaps.ssl.enable"));
        assertEquals("true", p.getProperty("mail.imaps.ssl.checkserveridentity"));
        assertEquals("false", p.getProperty("mail.imaps.socketFactory.fallback"));
        assertEquals("false", p.getProperty("mail.debug"));
        assertEquals("false", p.getProperty("mail.debug.auth.password"));
        assertNotNull(p.get("mail.imaps.ssl.socketFactory"));
        assertEquals("TLSv1.3 TLSv1.2", p.getProperty("mail.imaps.ssl.protocols"));
    }
    @Test void rejectsInvalidEndpointAndDisabledOrMalformedBindingBeforeConnection() throws Exception {
        SSLContext tls = SSLContext.getDefault();
        assertThrows(IllegalArgumentException.class, () -> new JamesImapSessionFactory("https://example", 993, tls));
        assertThrows(IllegalArgumentException.class, () -> new JamesImapSessionFactory("example", 0, tls));
        assertThrows(NullPointerException.class, () -> new JamesImapSessionFactory("example", 993, null));
        var factory = new JamesImapSessionFactory("james-imap", 993, tls);
        var mapping = new JamesMailboxBinding.ProvisionedMailbox(UUID.randomUUID(), false, "cvx-" + "a".repeat(32) + "@localhost", "account");
        assertThrows(IllegalArgumentException.class, () -> factory.connect(mapping, "x".repeat(43).toCharArray(), "INBOX"));
        var malformed = new JamesMailboxBinding.ProvisionedMailbox(UUID.randomUUID(), true, "other@example.com", "account");
        assertThrows(IllegalArgumentException.class, () -> factory.connect(malformed, "x".repeat(43).toCharArray(), "INBOX"));
    }
    @Test void explicitProviderDebugLevelFailsClosed() {
        Logger logger = Logger.getLogger("org.eclipse.angus.mail.imap.protocol");
        Level previous = logger.getLevel();
        try {
            logger.setLevel(Level.FINE);
            assertThrows(IOException.class, JamesImapSessionFactory::requireSafeLogging);
        } finally { logger.setLevel(previous); }
    }
}
