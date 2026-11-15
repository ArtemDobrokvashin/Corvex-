package ru.corvex.mailstorage.james;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.mail.MessagingException;
import org.eclipse.angus.mail.iap.Literal;
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;

/**
 * Disconnected adapter over Angus' IMAP implementation, not a Spring bean.
 * The caller must supply a dedicated already authenticated TLS connection,
 * verify hostname and recipient, map the trusted James account to that exact
 * connection and resolve an authorized IMAP mailbox name independently of JMAP
 * IDs. It must disable protocol debug and keep credentials out of logs. This
 * class provides none of those guarantees and is not production-ready.
 *
 * Raw literal avoids parsing/reserializing MIME. Missing APPENDUID or any error
 * is uncertain: even an error may follow a committed remote APPEND.
 */
final class JamesImapAppendTransport implements JamesDeliveryCoordinator.Transport {
    private final String account;
    private final String mailbox;
    private final IMAPFolder folder;
    private final AtomicBoolean attempted = new AtomicBoolean();

    JamesImapAppendTransport(String trustedAccount, String trustedMailbox, IMAPFolder authenticatedFolder) {
        if (trustedAccount == null || trustedAccount.isBlank() || trustedAccount.length() > 1024
                || trustedMailbox == null || trustedMailbox.isBlank() || trustedMailbox.length() > 1024) {
            throw new IllegalArgumentException("Invalid trusted IMAP target");
        }
        this.account = trustedAccount;
        this.mailbox = trustedMailbox;
        this.folder = Objects.requireNonNull(authenticatedFolder);
        if (!mailbox.equals(folder.getFullName())) throw new IllegalArgumentException("IMAP folder mismatch");
    }

    @Override
    public String appendOnce(String targetAccount, String targetFolder, InputStream frozenBytes, long size) throws IOException {
        if (!account.equals(targetAccount) || !mailbox.equals(targetFolder) || frozenBytes == null
                || size < 0 || size > JamesDeliveryCoordinator.MAX_BYTES) throw new IllegalArgumentException("IMAP target mismatch");
        // This object is one-shot even after connection failure: a new instance
        // must not be constructed to retry an operation in submitting/uncertain.
        if (!attempted.compareAndSet(false, true)) throw new IOException("IMAP append already attempted");
        var literal = new Literal() {
            private final AtomicBoolean written = new AtomicBoolean();
            @Override public int size() { return (int) size; }
            @Override public void writeTo(OutputStream out) throws IOException {
                if (!written.compareAndSet(false, true)) throw new IOException("Repeated IMAP literal");
                long copied = frozenBytes.transferTo(out);
                if (copied != size) throw new IOException("Incomplete IMAP literal");
            }
        };
        try {
            Object result = folder.doCommand(protocol -> {
                if (!protocol.hasCapability("UIDPLUS")) throw new org.eclipse.angus.mail.iap.ProtocolException("UIDPLUS required");
                return protocol.appenduid(mailbox, null, null, literal);
            });
            if (!(result instanceof AppendUID uid) || uid.uidvalidity <= 0 || uid.uid <= 0
                    || uid.uidvalidity > 0xffffffffL || uid.uid > 0xffffffffL) {
                throw new IOException("IMAP APPENDUID missing or invalid");
            }
            return uid.uidvalidity + ":" + uid.uid;
        } catch (MessagingException e) {
            // Angus exception text can contain server data: do not propagate it.
            throw new IOException("IMAP append outcome uncertain");
        }
    }
}
