package ru.corvex.james.av;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.apache.james.clamav.ClamAVScan;
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
    private volatile ClamdAddressCache addressCache;

    private static ScheduledThreadPoolExecutor createDeadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "corvex-clamd-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** Reject incomplete configuration instead of inheriting fail-open init. */
    @Override
    public void init() throws MessagingException {
        checkInitParameters(Set.of("host", "port"));
        String host = getInitParameter("host");
        if (host == null || host.isBlank() || !host.equals(host.trim())) {
            throw new MessagingException("ClamAV host must be explicitly configured");
        }
        String portValue = getInitParameter("port");
        int port = 3310;
        if (portValue != null) {
            if (!portValue.matches("[0-9]{1,5}")) throw new MessagingException("Invalid ClamAV port");
            port = Integer.parseInt(portValue);
            if (port < 1 || port > 65535) throw new MessagingException("Invalid ClamAV port");
        }
        try {
            InetAddress[] resolved = InetAddress.getAllByName(host);
            if (resolved.length == 0) throw new MessagingException("ClamAV host has no addresses");
            if (addressCache != null) throw new MessagingException("ClamAV gate already initialized");
            setAddresses(resolved);
            setPort(port);
            ClamdAddressCache cache = new ClamdAddressCache(host, resolved, InetAddress::getAllByName, System::nanoTime);
            addressCache = cache;
            try { cache.start(); }
            catch (RuntimeException e) {
                cache.close();
                throw new MessagingException("Cannot start ClamAV address refresh", e);
            }
        } catch (UnknownHostException e) {
            throw new MessagingException("Cannot resolve ClamAV host", e);
        }
        // No clamd PING here: an outage is handled as UNKNOWN per message.
    }

    @Override
    public void destroy() {
        ClamdAddressCache cache = addressCache;
        if (cache != null) cache.close();
    }

    @Override
    public void service(Mail mail) throws MessagingException {
        try {
            MimeMessage message = mail.getMessage();
            if (message == null) {
                mail.setState(UNKNOWN);
                return;
            }
            if (scanMessage(message)) mail.setState(INFECTED);
        } catch (IOException | MessagingException | RuntimeException e) {
            // Never trust sender headers or cached scan attributes; on any
            // failure do not allow LocalDelivery or RemoteDelivery to run.
            mail.setState(UNKNOWN);
        }
    }

    /** Bounded TCP connect to resolved addresses. No name lookup or retry after a scan begins. */
    @Override
    protected Socket getClamdSocket() throws ConnectException {
        ClamdAddressCache cache = addressCache;
        // Uninitialized instances are used only by the synthetic dependency tests.
        InetAddress[] configured = cache == null ? getAddresses() : cache.snapshot();
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

    @FunctionalInterface
    private interface ContentWriter {
        void write(OutputStream out) throws IOException, MessagingException;
    }

    // The watchdog bounds stalled socket writes and trickle responses. It cannot
    // interrupt MIME serialization or a source stream that itself hangs.
    boolean scan(InputStream input, long budgetNanos) throws IOException {
        try {
            return scanContent(out -> {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) throw new IOException("Input stream did not progress");
                    out.write(buffer, 0, count);
                }
            }, budgetNanos);
        } catch (MessagingException e) {
            throw new IOException("Unexpected MIME error", e);
        }
    }

    private boolean scanMessage(MimeMessage message) throws IOException, MessagingException {
        return scanContent(message::writeTo, SCAN_BUDGET_NANOS);
    }

    private boolean scanContent(ContentWriter content, long budgetNanos) throws IOException, MessagingException {
        if (budgetNanos <= 0) throw new IOException("Invalid scan deadline");
        try (Socket socket = getClamdSocket()) {
            long start = System.nanoTime();
            ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
                try { socket.close(); } catch (IOException ignored) { }
            }, budgetNanos, TimeUnit.NANOSECONDS);
            try {
                socket.setSoTimeout(READ_TIMEOUT_MS);
                DataOutputStream protocol = new DataOutputStream(socket.getOutputStream());
                protocol.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                OutputStream chunks = new BufferedOutputStream(new ChunkOutput(protocol), 16384);
                content.write(chunks);
                chunks.flush();
                protocol.writeInt(0);
                protocol.flush();
                boolean infected = parseReply(socket.getInputStream());
                if (System.nanoTime() - start >= budgetNanos) throw new IOException("Scan deadline exceeded");
                return infected;
            } finally {
                deadline.cancel(false);
            }
        }
    }

    /** Limit bytes before framing; never close the socket through the MIME writer. */
    private static final class ChunkOutput extends OutputStream {
        private final DataOutputStream protocol;
        private long total;
        private ChunkOutput(DataOutputStream protocol) { this.protocol = protocol; }
        @Override public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return;
            if (length > MAX_BYTES - total) throw new IOException("Scan size limit exceeded");
            total += length;
            protocol.writeInt(length);
            protocol.write(bytes, offset, length);
        }
        @Override public void flush() throws IOException { protocol.flush(); }
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
                    if (!signature.isBlank() && signature.chars().allMatch(ch -> ch >= 32 && ch <= 126)) {
                        // A configured ClamAV limit alert is not proof of infection
                        // or of a complete scan; quarantine as UNKNOWN.
                        if (signature.startsWith("Heuristics.Limits.Exceeded")) {
                            throw new IOException("Scanner limit exceeded");
                        }
                        return true;
                    }
                }
                throw new IOException("Unknown scanner reply");
            }
            bytes[count++] = (byte) value;
        }
        throw new IOException("Scanner reply too long");
    }
}
