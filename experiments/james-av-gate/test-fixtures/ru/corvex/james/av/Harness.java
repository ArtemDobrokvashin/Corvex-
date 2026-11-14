package ru.corvex.james.av;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import jakarta.mail.internet.MimeMessage;
import org.apache.mailet.Mail;
import org.apache.james.clamav.ClamAVScan;

/** Synthetic only: fake James API and local fake scanner, not an integration test. */
public class Harness {
    static class Message implements Mail {
        String state = "root";
        MimeMessage message = new MimeMessage();
        public MimeMessage getMessage() { return message; }
        public void setState(String state) { this.state = state; }
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void configure(ServerSocket server) throws Exception {
        ClamAVScan.addresses = new InetAddress[]{InetAddress.getLoopbackAddress()};
        ClamAVScan.port = server.getLocalPort();
    }
    static void reply(String reply, String expected) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            configure(server);
            java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
            Thread thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    in.readNBytes(10);
                    for (int n; (n = in.readInt()) != 0;) in.readNBytes(n);
                    socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                } catch (IOException e) { error.set(e); }
            });
            thread.start();
            Message message = new Message();
            new CorvexClamAVGate().service(message);
            thread.join(2000);
            check(!thread.isAlive() && error.get() == null && message.state.equals(expected));
        }
    }
    static void framingAndLimit(boolean oversize) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            configure(server);
            java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicLong received = new java.util.concurrent.atomic.AtomicLong();
            java.util.concurrent.atomic.AtomicBoolean terminated = new java.util.concurrent.atomic.AtomicBoolean();
            Thread thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    check(java.util.Arrays.equals(in.readNBytes(10), "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII)));
                    while (true) {
                        int n = in.readInt();
                        if (n == 0) { terminated.set(true); break; }
                        check(n > 0 && n <= 16384);
                        byte[] chunk = in.readNBytes(n);
                        check(chunk.length == n);
                        for (byte value : chunk) check(value == 42);
                        received.addAndGet(n);
                    }
                    socket.getOutputStream().write("stream: OK\0".getBytes(StandardCharsets.US_ASCII));
                } catch (EOFException e) {
                    if (!oversize) error.set(e);
                } catch (Throwable e) { error.set(e); }
            });
            thread.start();
            InputStream input = new InputStream() {
                long left = oversize ? 25L * 1024 * 1024 + 1 : 33000;
                public int read() { if (left == 0) return -1; left--; return 42; }
                public int read(byte[] b, int off, int len) {
                    if (left == 0) return -1;
                    int count = (int) Math.min(left, len);
                    java.util.Arrays.fill(b, off, off + count, (byte) 42);
                    left -= count;
                    return count;
                }
            };
            try {
                boolean result = new CorvexClamAVGate().scan(input, TimeUnit.SECONDS.toNanos(10));
                check(!oversize && !result);
            } catch (IOException e) {
                check(oversize && e.getMessage().contains("limit"));
            }
            thread.join(3000);
            check(!thread.isAlive() && error.get() == null);
            if (oversize) check(!terminated.get() && received.get() <= 25L * 1024 * 1024);
            else check(terminated.get() && received.get() == 33000);
        }
    }
    static void timeout(boolean writeStall) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setReceiveBufferSize(4096);
            configure(server);
            java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
            Thread thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    if (!writeStall) {
                        DataInputStream in = new DataInputStream(socket.getInputStream());
                        in.readNBytes(10);
                        for (int n; (n = in.readInt()) != 0;) in.readNBytes(n);
                    }
                    Thread.sleep(1000);
                } catch (Exception e) { error.set(e); }
            });
            thread.start();
            InputStream input = writeStall ? new InputStream() {
                long left = 25L * 1024 * 1024;
                public int read() { return left-- > 0 ? 42 : -1; }
                public int read(byte[] b, int off, int len) {
                    if (left == 0) return -1;
                    int count = (int) Math.min(left, len);
                    java.util.Arrays.fill(b, off, off + count, (byte) 42);
                    left -= count;
                    return count;
                }
            } : new ByteArrayInputStream(new byte[]{1});
            long started = System.nanoTime();
            try {
                new CorvexClamAVGate().scan(input, TimeUnit.MILLISECONDS.toNanos(150));
                throw new AssertionError("deadline accepted stalled scanner");
            } catch (IOException expected) {
                long elapsed = System.nanoTime() - started;
                check(elapsed >= TimeUnit.MILLISECONDS.toNanos(75) && elapsed < TimeUnit.SECONDS.toNanos(3));
            }
            thread.join(3000);
            check(!thread.isAlive() && error.get() == null);
        }
    }
    static void configuration() throws Exception {
        for (java.util.Map<String, String> bad : java.util.List.of(
                java.util.Map.<String, String>of(),
                java.util.Map.of("host", " "),
                java.util.Map.of("host", "127.0.0.1", "port", "0"),
                java.util.Map.of("host", "127.0.0.1", "port", "65536"),
                java.util.Map.of("host", "127.0.0.1", "port", "+3310"),
                java.util.Map.of("host", "127.0.0.1", "maxPings", "1"))) {
            CorvexClamAVGate gate = new CorvexClamAVGate(); gate.parameters = bad;
            try { gate.init(); throw new AssertionError("invalid configuration accepted"); }
            catch (jakarta.mail.MessagingException expected) { }
        }
        CorvexClamAVGate good = new CorvexClamAVGate();
        good.parameters = java.util.Map.of("host", "127.0.0.1", "port", "12345");
        good.init();
        check(ClamAVScan.addresses.length == 1 && ClamAVScan.port == 12345);
    }
    public static void main(String[] args) throws Exception {
        configuration();
        reply("stream: OK\0", "root");
        reply("stream: Eicar-Test FOUND\0", CorvexClamAVGate.INFECTED);
        for (String bad : new String[]{"stream: BAD OK\0", "stream: OK", "stream: failure ERROR\0", "", "stream:  FOUND\0"})
            reply(bad, CorvexClamAVGate.UNKNOWN);
        Message m = new Message(); m.message = null;
        new CorvexClamAVGate().service(m);
        check(m.state.equals(CorvexClamAVGate.UNKNOWN));
        ClamAVScan.addresses = null; m = new Message();
        new CorvexClamAVGate().service(m);
        check(m.state.equals(CorvexClamAVGate.UNKNOWN));
        framingAndLimit(false); framingAndLimit(true);
        timeout(false); timeout(true);
        System.out.println("PASS: synthetic James stubs and fake scanner, including stalled read/write");
    }
}
