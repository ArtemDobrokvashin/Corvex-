import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Проверка всех трёх Java-клиентов ClamAV с настоящим clamd через zINSTREAM.
 * Использует только синтетические данные: чистый поток и EICAR test string.
 * Запускается внутри контейнера в сети app, обращаясь к clamav:3310.
 *
 * Аргументы:
 *   args[0] — хост clamd (например, clamav)
 *   args[1] — порт clamd (например, 3310)
 *   args[2] — порт для проверки недоступности (например, 13310)
 */
public class Av001ClientCheck {

    private static final byte[] CLEAN_PAYLOAD =
        "synthetic clean stream content for clamav av-001 check".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EICAR =
        "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
        .getBytes(StandardCharsets.US_ASCII);

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: Av001ClientCheck <clamav-host> <clamav-port> <unavailable-port>");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int unavailablePort = Integer.parseInt(args[2]);

        System.out.println("AV-001: проверка трёх Java-клиентов ClamAV через настоящий clamd (" + host + ":" + port + ")");
        System.out.println("");

        // --- mail-antivirus-service ---
        check("mail-antivirus-service: clean stream",
                scanMailAv(CLEAN_PAYLOAD, host, port),
                ClamAvClientResult.Expected.CLEAN);
        check("mail-antivirus-service: EICAR",
                scanMailAv(EICAR, host, port),
                ClamAvClientResult.Expected.INFECTED);
        check("mail-antivirus-service: unavailable scanner",
                scanMailAv(CLEAN_PAYLOAD, host, unavailablePort),
                ClamAvClientResult.Expected.ERROR);

        // --- mail-storage-service ---
        check("mail-storage-service: clean stream",
                scanMailStorage(CLEAN_PAYLOAD, host, port),
                ClamAvClientResult.Expected.CLEAN);
        check("mail-storage-service: EICAR",
                scanMailStorage(EICAR, host, port),
                ClamAvClientResult.Expected.INFECTED);
        check("mail-storage-service: unavailable scanner",
                scanMailStorage(CLEAN_PAYLOAD, host, unavailablePort),
                ClamAvClientResult.Expected.ERROR);

        // --- chat-file-service ---
        check("chat-file-service: clean stream",
                scanChatFile(CLEAN_PAYLOAD, host, port),
                ClamAvClientResult.Expected.CLEAN);
        check("chat-file-service: EICAR",
                scanChatFile(EICAR, host, port),
                ClamAvClientResult.Expected.INFECTED);
        check("chat-file-service: unavailable scanner",
                scanChatFile(CLEAN_PAYLOAD, host, unavailablePort),
                ClamAvClientResult.Expected.ERROR);

        System.out.println("");
        System.out.println("Result: passed=" + passed + " failed=" + failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    // --- mail-antivirus-service ---
    private static ClamAvClientResult scanMailAv(byte[] payload, String host, int port) {
        try {
            ru.corvex.mailav.ClamAvClient client = new ru.corvex.mailav.ClamAvClient(host, port);
            ru.corvex.mailav.ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(payload));
            return new ClamAvClientResult(result.result(), result.signature());
        } catch (Exception e) {
            return new ClamAvClientResult(ClamAvClientResult.Result.ERROR, e.getMessage());
        }
    }

    // --- mail-storage-service ---
    private static ClamAvClientResult scanMailStorage(byte[] payload, String host, int port) {
        try {
            ru.corvex.mailstorage.av.ClamAvClient client = new ru.corvex.mailstorage.av.ClamAvClient(host, port);
            ru.corvex.mailstorage.av.ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(payload));
            return new ClamAvClientResult(result.result(), result.signature());
        } catch (Exception e) {
            return new ClamAvClientResult(ClamAvClientResult.Result.ERROR, e.getMessage());
        }
    }

    // --- chat-file-service ---
    private static ClamAvClientResult scanChatFile(byte[] payload, String host, int port) {
        try {
            ru.corvex.chatfile.av.ClamAvClient client = new ru.corvex.chatfile.av.ClamAvClient(host, port);
            ru.corvex.chatfile.av.ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(payload));
            return new ClamAvClientResult(result.result(), result.signature());
        } catch (Exception e) {
            return new ClamAvClientResult(ClamAvClientResult.Result.ERROR, e.getMessage());
        }
    }

    private static void check(String label, ClamAvClientResult result, ClamAvClientResult.Expected expected) {
        boolean ok;
        switch (expected) {
            case CLEAN:
                ok = result.result() == ClamAvClientResult.Result.CLEAN;
                break;
            case INFECTED:
                ok = result.result() == ClamAvClientResult.Result.INFECTED;
                break;
            case ERROR:
                ok = result.result() == ClamAvClientResult.Result.ERROR;
                break;
            default:
                ok = false;
        }
        if (ok) {
            System.out.println("PASS: " + label + " -> " + result.result()
                    + (result.signature() != null ? " (signature: " + result.signature() + ")" : ""));
            passed++;
        } else {
            System.out.println("FAIL: " + label + " -> " + result.result()
                    + (result.signature() != null ? " (signature: " + result.signature() + ")" : "")
                    + ", expected: " + expected);
            failed++;
        }
    }

    /**
     * Обёртка над результатом сканирования (три разных класса ScanResult с одинаковой структурой).
     */
    private static final class ClamAvClientResult {
        enum Result {CLEAN, INFECTED, ERROR}
        enum Expected {CLEAN, INFECTED, ERROR}

        private final Result result;
        private final String signature;

        private ClamAvClientResult(Result result, String signature) {
            this.result = result;
            this.signature = signature;
        }
    }
}
