import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Проверка трёх Java-клиентов Corvex ClamAV с настоящим clamd через zINSTREAM.
 *
 * Вызывает scan(InputStream) каждого из трёх классов напрямую (без рефлексии,
 * без повторной реализации протокола):
 *   1. ru.corvex.mailav.ClamAvClient
 *   2. ru.corvex.mailstorage.av.ClamAvClient
 *   3. ru.corvex.chatfile.av.ClamAvClient
 *
 * Сценарии для КАЖДОГО класса (всего 9 результатов):
 *   - синтетический чистый поток          -> Result.CLEAN
 *   - стандартный EICAR test string        -> Result.INFECTED
 *   - контролируемый fake endpoint         -> Result.ERROR (имитация сбоя сканера)
 *
 * Аргументы:
 *   args[0] — хост clamd (например, clamav)
 *   args[1] — порт clamd (например, 3310)
 *   args[2] — порт локального fake endpoint для имитации сбоя
 */
public class Av002ClientCheck {

    private static final byte[] CLEAN_PAYLOAD =
        "synthetic clean stream content for clamav av-002 check".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EICAR =
        "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
        .getBytes(StandardCharsets.US_ASCII);

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: Av002ClientCheck <clamav-host> <clamav-port> <fake-endpoint-port>");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int fakePort = Integer.parseInt(args[2]);

        System.out.println("AV-002: проверка трёх Java-клиентов ClamAV через настоящий clamd (" + host + ":" + port + ")");
        System.out.println("  Имитация сбоя сканера через локальный fake endpoint: " + fakePort);
        System.out.println("  (Это имитация сбоя, НЕ остановка настоящего ClamAV)");
        System.out.println("");

        // Fake endpoint принимает 3 соединения (по одному на каждый сервис для ERROR сценария)
        try (FakeEndpoint fake = new FakeEndpoint(fakePort, 3)) {
            // --- mail-antivirus-service ---
            check("mail-antivirus-service: clean stream",
                    scanMailAv(CLEAN_PAYLOAD, host, port),
                    ResultEx.CLEAN);
            check("mail-antivirus-service: EICAR",
                    scanMailAv(EICAR, host, port),
                    ResultEx.INFECTED);
            check("mail-antivirus-service: scanner failure (fake endpoint)",
                    scanMailAv(CLEAN_PAYLOAD, "127.0.0.1", fakePort),
                    ResultEx.ERROR);

            // --- mail-storage-service ---
            check("mail-storage-service: clean stream",
                    scanMailStorage(CLEAN_PAYLOAD, host, port),
                    ResultEx.CLEAN);
            check("mail-storage-service: EICAR",
                    scanMailStorage(EICAR, host, port),
                    ResultEx.INFECTED);
            check("mail-storage-service: scanner failure (fake endpoint)",
                    scanMailStorage(CLEAN_PAYLOAD, "127.0.0.1", fakePort),
                    ResultEx.ERROR);

            // --- chat-file-service ---
            check("chat-file-service: clean stream",
                    scanChatFile(CLEAN_PAYLOAD, host, port),
                    ResultEx.CLEAN);
            check("chat-file-service: EICAR",
                    scanChatFile(EICAR, host, port),
                    ResultEx.INFECTED);
            check("chat-file-service: scanner failure (fake endpoint)",
                    scanChatFile(CLEAN_PAYLOAD, "127.0.0.1", fakePort),
                    ResultEx.ERROR);
        } catch (Exception e) {
            System.out.println("FAIL: не удалось запустить fake endpoint: " + e.getMessage());
            failed += 3; // все три ERROR-теста не могут быть выполнены
            System.out.println("");
            System.out.println("Result: passed=" + passed + " failed=" + failed + " skipped=" + skipped);
            System.exit(1);
        }

        System.out.println("");
        System.out.println("Result: passed=" + passed + " failed=" + failed + " skipped=" + skipped);
        System.exit(failed > 0 ? 1 : 0);
    }

    // --- mail-antivirus-service ---
    private static CheckResult scanMailAv(byte[] payload, String host, int port) {
        try {
            ru.corvex.mailav.ClamAvClient client =
                new ru.corvex.mailav.ClamAvClient(host, port, 5000, 15000);
            ru.corvex.mailav.ClamAvClient.ScanResult r =
                client.scan(new ByteArrayInputStream(payload));
            return new CheckResult(normalize(r.result()), r.signature());
        } catch (Exception e) {
            return new CheckResult(ResultEx.ERROR, e.getMessage());
        }
    }

    // --- mail-storage-service ---
    private static CheckResult scanMailStorage(byte[] payload, String host, int port) {
        try {
            ru.corvex.mailstorage.av.ClamAvClient client =
                new ru.corvex.mailstorage.av.ClamAvClient(host, port, 5000, 15000);
            ru.corvex.mailstorage.av.ClamAvClient.ScanResult r =
                client.scan(new ByteArrayInputStream(payload));
            return new CheckResult(normalize(r.result()), r.signature());
        } catch (Exception e) {
            return new CheckResult(ResultEx.ERROR, e.getMessage());
        }
    }

    // --- chat-file-service ---
    private static CheckResult scanChatFile(byte[] payload, String host, int port) {
        try {
            ru.corvex.chatfile.av.ClamAvClient client =
                new ru.corvex.chatfile.av.ClamAvClient(host, port, 5000, 15000);
            ru.corvex.chatfile.av.ClamAvClient.ScanResult r =
                client.scan(new ByteArrayInputStream(payload));
            return new CheckResult(normalize(r.result()), r.signature());
        } catch (Exception e) {
            return new CheckResult(ResultEx.ERROR, e.getMessage());
        }
    }

    private static ResultEx normalize(ru.corvex.mailav.ClamAvClient.Result r) {
        switch (r) {
            case CLEAN: return ResultEx.CLEAN;
            case INFECTED: return ResultEx.INFECTED;
            case ERROR: return ResultEx.ERROR;
            default: return ResultEx.ERROR;
        }
    }

    private static ResultEx normalize(ru.corvex.mailstorage.av.ClamAvClient.Result r) {
        switch (r) {
            case CLEAN: return ResultEx.CLEAN;
            case INFECTED: return ResultEx.INFECTED;
            case ERROR: return ResultEx.ERROR;
            default: return ResultEx.ERROR;
        }
    }

    private static ResultEx normalize(ru.corvex.chatfile.av.ClamAvClient.Result r) {
        switch (r) {
            case CLEAN: return ResultEx.CLEAN;
            case INFECTED: return ResultEx.INFECTED;
            case ERROR: return ResultEx.ERROR;
            default: return ResultEx.ERROR;
        }
    }

    private static void check(String label, CheckResult result, ResultEx expected) {
        boolean ok = (result.result == expected);
        if (ok) {
            System.out.println("PASS: " + label + " -> " + result.result
                    + (result.signature != null ? " (signature: " + result.signature + ")" : ""));
            passed++;
        } else {
            System.out.println("FAIL: " + label + " -> " + result.result
                    + (result.signature != null ? " (signature: " + result.signature + ")" : "")
                    + ", expected: " + expected);
            failed++;
        }
    }

    /**
     * Локальный fake endpoint для имитации сбоя сканера.
     * Принимает N соединений, читает zINSTREAM-запрос от каждого, но никогда не отвечает.
     * Каждый клиент с коротким read-таймаутом получит SocketTimeoutException -> ERROR.
     * Это имитация сбоя, НЕ остановка настоящего ClamAV.
     */
    private static final class FakeEndpoint implements AutoCloseable {
        private final ServerSocket server;
        private final Thread handler;

        private FakeEndpoint(int port, int expectedConnections) throws Exception {
            this.server = new ServerSocket(port);
            this.server.setReuseAddress(true);
            this.server.setSoTimeout(60_000);
            this.handler = new Thread(() -> serve(expectedConnections), "av002-fake-endpoint");
            this.handler.start();
        }

        private void serve(int expectedConnections) {
            try {
                for (int i = 0; i < expectedConnections; i++) {
                    Socket client = server.accept();
                    client.setSoTimeout(10_000);
                    Thread t = new Thread(() -> handleClient(client), "fake-endpoint-client-" + i);
                    t.start();
                    t.join(20_000);
                }
            } catch (Exception ignored) {
            }
        }

        private void handleClient(Socket client) {
            try {
                java.io.DataInputStream in = new java.io.DataInputStream(client.getInputStream());
                // Читаем команду (null-terminated)
                StringBuilder cmd = new StringBuilder();
                int b;
                while ((b = in.read()) != -1 && b != 0) {
                    cmd.append((char) b);
                }
                // Читаем чанки до нулевого терминатора
                while (true) {
                    int length = in.readInt();
                    if (length == 0) {
                        break;
                    }
                    byte[] chunk = new byte[length];
                    int total = 0;
                    while (total < length) {
                        int read = in.read(chunk, total, length - total);
                        if (read < 0) {
                            return;
                        }
                        total += read;
                    }
                }
                // Никогда не отвечаем, никогда не закрываем — клиент упрется в read-таймаут
            } catch (Exception ignored) {
            } finally {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public void close() throws Exception {
            handler.join(30_000);
            try {
                server.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Нормализованный результат сканирования (три разных Result enum в трёх пакетах).
     */
    private static final class CheckResult {
        ResultEx result;
        String signature;

        CheckResult(ResultEx result, String signature) {
            this.result = result;
            this.signature = signature;
        }
    }

    /**
     * Единый enum результата для сравнения трёх разных Result клиентов.
     */
    private enum ResultEx {CLEAN, INFECTED, ERROR}
}
