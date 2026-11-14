package ru.corvex.james.av;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.james.clamav.ClamAVScan;
import org.apache.james.server.core.MimeMessageInputStream;
import org.apache.mailet.Mail;

/**
 * EXPERIMENT, not registered or shipped in James. Routes uncertain scans away
 * from normal processing; requires explicit quarantine processors and tests.
 * No MIME header or previously attached mail attribute is trusted as a result.
 * This is not a complete fail-closed system: pipeline coverage, durability,
 * ClamAV limits, connection/write timeouts and class loading remain unverified.
 */
public final class CorvexClamAVGate extends ClamAVScan {
    static final String INFECTED = "corvex-av-infected";
    static final String UNKNOWN = "corvex-av-unknown";
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final int REPLY_LIMIT = 4096;
    private static final int READ_TIMEOUT_MS = 5000;

    @Override
    public void service(Mail mail) throws MessagingException {
        try {
            MimeMessage message = mail.getMessage();
            if (message == null) {
                mail.setState(UNKNOWN);
                return;
            }
            try (InputStream input = new MimeMessageInputStream(message)) {
                if (hasVirus(input)) mail.setState(INFECTED);
            }
        } catch (IOException | MessagingException | RuntimeException e) {
            // Never trust sender headers or cached scan attributes; on any
            // failure do not allow LocalDelivery or RemoteDelivery to run.
            mail.setState(UNKNOWN);
        }
    }

    /** ClamAV INSTREAM; false only for the exact, NUL-framed clean response. */
    @Override
    public boolean hasVirus(InputStream input) throws IOException {
        try (Socket socket = getClamdSocket()) {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[16384];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) throw new IOException("Input stream did not progress");
                total += count;
                if (total > MAX_BYTES) throw new IOException("Scan size limit exceeded");
                out.writeInt(count);
                out.write(buffer, 0, count);
            }
            out.writeInt(0);
            out.flush();
            return parseReply(socket.getInputStream());
        }
    }

    static boolean parseReply(InputStream input) throws IOException {
        byte[] bytes = new byte[REPLY_LIMIT];
        int count = 0;
        while (count < bytes.length) {
            int value = input.read();
            if (value < 0) throw new IOException("Truncated scanner reply");
            if (value == 0) {
                byte[] reply = Arrays.copyOf(bytes, count);
                if (Arrays.equals(reply, "stream: OK".getBytes(StandardCharsets.US_ASCII))) return false;
                String text = new String(reply, StandardCharsets.ISO_8859_1);
                if (text.startsWith("stream: ") && text.endsWith(" FOUND")) {
                    String signature = text.substring(8, text.length() - 6);
                    if (!signature.isBlank() && signature.chars().allMatch(ch -> ch >= 32 && ch <= 126)) return true;
                }
                throw new IOException("Unknown scanner reply");
            }
            bytes[count++] = (byte) value;
        }
        throw new IOException("Scanner reply too long");
    }
}
