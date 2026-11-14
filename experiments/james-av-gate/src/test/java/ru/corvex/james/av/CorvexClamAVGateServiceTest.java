package ru.corvex.james.av;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.apache.james.clamav.ClamAVScan;
import org.apache.mailet.Mail;
import org.junit.jupiter.api.Test;

/** Real James/Jakarta APIs, but only local fake clamd and fake Mail: not an integration test. */
class CorvexClamAVGateServiceTest {
    private static final byte[] MIME = ("From: sender@example.invalid\r\n"
        + "To: recipient@example.invalid\r\n"
        + "Subject: synthetic\r\n"
        + "X-Virus-Status: clean\r\n"
        + "Content-Type: text/plain\r\n\r\nunique-synthetic-payload\r\n")
        .getBytes(StandardCharsets.US_ASCII);

    private static Mail mail(MimeMessage message, AtomicReference<String> state) {
        return (Mail) Proxy.newProxyInstance(Mail.class.getClassLoader(), new Class<?>[]{Mail.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getMessage" -> message;
                case "setState" -> { state.set((String) args[0]); yield null; }
                default -> throw new AssertionError("Unexpected Mail method: " + method.getName());
            });
    }

    private void runCase(String response, String expected) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(3000);
            var scan = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    assertArrayEquals("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII), in.readNBytes(10));
                    ByteArrayOutputStream captured = new ByteArrayOutputStream();
                    for (int n; (n = in.readInt()) != 0;) {
                        if (n < 0 || n > 16384) throw new IOException("Invalid chunk");
                        byte[] chunk = in.readNBytes(n);
                        if (chunk.length != n) throw new IOException("Truncated chunk");
                        captured.write(chunk);
                        if (captured.size() > 10000) throw new IOException("Unexpected size");
                    }
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    return captured.toString(StandardCharsets.US_ASCII);
                }
            });
            CorvexClamAVGate gate = new CorvexClamAVGate();
            var setAddresses = ClamAVScan.class.getDeclaredMethod("setAddresses", InetAddress[].class);
            setAddresses.setAccessible(true);
            setAddresses.invoke(gate, (Object) new InetAddress[]{InetAddress.getLoopbackAddress()});
            gate.setPort(server.getLocalPort());
            MimeMessage mime = new MimeMessage(null, new ByteArrayInputStream(MIME));
            AtomicReference<String> state = new AtomicReference<>("transport");
            gate.service(mail(mime, state));
            assertEquals(expected, state.get());
            assertTrue(scan.get(5, TimeUnit.SECONDS).contains("unique-synthetic-payload"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void cleanInfectedAndUnknownAreRoutedWithoutTrustingHeaders() throws Exception {
        runCase("stream: OK\0", "transport");
        runCase("stream: Test-Signature FOUND\0", CorvexClamAVGate.INFECTED);
        runCase("stream: ERROR\0", CorvexClamAVGate.UNKNOWN);
        runCase("stream: OK", CorvexClamAVGate.UNKNOWN);
    }

    @Test void missingOrUnreadableMimeIsUnknown() throws Exception {
        AtomicReference<String> state = new AtomicReference<>("transport");
        CorvexClamAVGate gate = new CorvexClamAVGate();
        gate.service(mail(null, state));
        assertEquals(CorvexClamAVGate.UNKNOWN, state.get());
        Mail broken = (Mail) Proxy.newProxyInstance(Mail.class.getClassLoader(), new Class<?>[]{Mail.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getMessage" -> throw new MessagingException("synthetic error");
                case "setState" -> { state.set((String) args[0]); yield null; }
                default -> throw new AssertionError("Unexpected Mail method: " + method.getName());
            });
        state.set("transport");
        gate.service(broken);
        assertEquals(CorvexClamAVGate.UNKNOWN, state.get());
    }
}
