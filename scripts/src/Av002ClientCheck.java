import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic AV client probe. No mail is submitted. Not a James AV acceptance test. */
public final class Av002ClientCheck {
    private static final byte[] CLEAN = "corvex synthetic harmless text".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EICAR = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
            .getBytes(StandardCharsets.US_ASCII);
    private enum Result { CLEAN, INFECTED, ERROR }
    @FunctionalInterface private interface Scan { Result run(String host, int port, byte[] input); }
    private record Client(String name, Scan scan) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected host and port");
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        Client[] clients = {
            new Client("mail-antivirus", (h, p, b) -> Result.valueOf(new ru.corvex.mailav.ClamAvClient(h, p, 3000, 5000).scan(new ByteArrayInputStream(b)).result().name())),
            new Client("mail-storage", (h, p, b) -> Result.valueOf(new ru.corvex.mailstorage.av.ClamAvClient(h, p, 3000, 5000).scan(new ByteArrayInputStream(b)).result().name())),
            new Client("chat-file", (h, p, b) -> Result.valueOf(new ru.corvex.chatfile.av.ClamAvClient(h, p, 3000, 5000).scan(new ByteArrayInputStream(b)).result().name()))
        };
        int passed = 0;
        try (FakeErrorEndpoint fake = new FakeErrorEndpoint()) {
            for (Client client : clients) {
                passed += check(client.name(), "clean", client.scan().run(host, port, CLEAN), Result.CLEAN);
                passed += check(client.name(), "eicar", client.scan().run(host, port, EICAR), Result.INFECTED);
                passed += check(client.name(), "simulated-error", client.scan().run("127.0.0.1", fake.port(), CLEAN), Result.ERROR);
            }
            fake.verify();
        }
        System.out.println("AV-002: " + passed + "/9 PASS; simulated error is not a real outage.");
        if (passed != 9) System.exit(1);
    }

    private static int check(String client, String kind, Result actual, Result expected) {
        boolean ok = actual == expected;
        System.out.println((ok ? "PASS " : "FAIL ") + client + " " + kind + " -> " + actual);
        return ok ? 1 : 0;
    }

    /** Fixed error response, loopback-only and ephemeral. Not a read-timeout test. */
    private static final class FakeErrorEndpoint implements AutoCloseable {
        private final ServerSocket server = new ServerSocket();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;
        FakeErrorEndpoint() throws IOException {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            server.setSoTimeout(20000);
            thread = new Thread(this::serve, "synthetic-clamd-error");
            thread.setDaemon(true);
            thread.start();
        }
        int port() { return server.getLocalPort(); }
        private void serve() {
            try {
                for (int i = 0; i < 3; i++) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        DataInputStream in = new DataInputStream(socket.getInputStream());
                        byte[] command = in.readNBytes(10);
                        if (!java.util.Arrays.equals(command, "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII))) throw new IOException("bad command");
                        int count = 0, length;
                        while ((length = in.readInt()) != 0) {
                            if (length < 0 || length > 65536 || (count += length) > 65536) throw new IOException("invalid chunk");
                            if (in.readNBytes(length).length != length) throw new IOException("short chunk");
                        }
                        socket.getOutputStream().write("stream: simulated ERROR\0".getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                }
            } catch (IOException | RuntimeException e) { failure.set(e); }
        }
        void verify() throws Exception {
            thread.join(10000);
            if (thread.isAlive() || failure.get() != null) throw new IOException("Fake endpoint did not complete correctly");
        }
        @Override public void close() throws IOException { server.close(); }
    }
}
