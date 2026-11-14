package ru.corvex.james.av;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
 * ClamAV limits, MIME input stalls and class loading remain unverified.
 */
public final class CorvexClamAVGate extends ClamAVScan {
    static final String INFECTED = "corvex-av-infected";
    static final String UNKNOWN = "corvex-av-unknown";
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final int REPLY_LIMIT = 4096;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final long CONNECT_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final int MAX_ADDRESSES = 4;
    private static final long SCAN_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final ScheduledThreadPoolExecutor DEADLINES = createDeadlineExecutor();

    private static ScheduledThreadPoolExecutor createDeadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "corvex-clamd-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

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

    /** Bounded TCP connect to resolved addresses. No name lookup or retry after a scan begins. */
    @Override
    protected Socket getClamdSocket() throws ConnectException {
        InetAddress[] configured = getAddresses();
        int port = getPort();
        if (configured == null || configured.length == 0 || port < 1 || port > 65535) {
            throw new ConnectException("ClamAV is not configured");
        }
        InetAddress[] addresses = Arrays.copyOf(configured, Math.min(configured.length, MAX_ADDRESSES));
        long deadline = System.nanoTime() + CONNECT_BUDGET_NANOS;
        for (InetAddress address : addresses) {
            if (address == null) continue;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            int timeout = (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, port), timeout);
                return socket;
            } catch (IOException e) {
                try { socket.close(); } catch (IOException ignored) { }
            }
        }
        throw new ConnectException("ClamAV connection unavailable");
    }

    /** ClamAV INSTREAM; false only for the exact, NUL-framed clean response. */
    @Override
    public boolean hasVirus(InputStream input) throws IOException {
        return scan(input, SCAN_BUDGET_NANOS);
    }

    // The watchdog bounds stalled socket writes and trickle responses. It cannot
    // interrupt a MIME input stream that itself hangs; that remains a blocker.
    boolean scan(InputStream input, long budgetNanos) throws IOException {
        if (budgetNanos <= 0) throw new IOException("Invalid scan deadline");
        try (Socket socket = getClamdSocket()) {
            long start = System.nanoTime();
            ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
                try { socket.close(); } catch (IOException ignored) { }
            }, budgetNanos, TimeUnit.NANOSECONDS);
            try {
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
                boolean infected = parseReply(socket.getInputStream());
                if (System.nanoTime() - start >= budgetNanos) throw new IOException("Scan deadline exceeded");
                return infected;
            } finally {
                deadline.cancel(false);
            }
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
