import ru.corvex.mailstorage.av.ClamAvClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Synthetic EICAR and small nested ZIPs only. No mail is sent or read. */
public final class AvNestedArchiveProbe {
    private static final byte[] EICAR = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
            .getBytes(StandardCharsets.US_ASCII);
    private static final int DEEP_LEVELS = 25;
    private static final int MAX_BYTES = 128 * 1024;

    private static byte[] nested(int levels) throws IOException {
        byte[] data = EICAR;
        for (int i = 0; i < levels; i++) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buf)) {
                zip.putNextEntry(new ZipEntry(i == 0 ? "test.txt" : "nested.zip"));
                zip.write(data);
                zip.closeEntry();
            }
            data = buf.toByteArray();
            if (data.length > MAX_BYTES) throw new IOException("Synthetic archive too large");
        }
        return data;
    }

    private static void verifyNested(byte[] archive, int levels) throws IOException {
        byte[] data = archive;
        for (int i = 0; i < levels; i++) {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
                if (zip.getNextEntry() == null) throw new IOException("Missing synthetic ZIP entry");
                data = zip.readNBytes(MAX_BYTES + 1);
                if (data.length > MAX_BYTES || zip.getNextEntry() != null) {
                    throw new IOException("Invalid synthetic ZIP contents");
                }
            }
        }
        if (!Arrays.equals(EICAR, data)) throw new IOException("Invalid synthetic ZIP payload");
    }

    private static ClamAvClient.ScanResult scan(ClamAvClient client, byte[] content) {
        return client.scan(new ByteArrayInputStream(content));
    }

    private static String category(ClamAvClient.ScanResult result) {
        if (result.result() != ClamAvClient.Result.INFECTED) return result.result().name();
        String signature = result.signature();
        if (signature != null && signature.startsWith("Heuristics.Limits.Exceeded")) return "LIMIT_ALERT";
        if (signature != null && signature.toLowerCase(java.util.Locale.ROOT).contains("eicar")) return "EICAR_FOUND";
        return "OTHER_FOUND";
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"clamav".equals(args[0]) || !"3310".equals(args[1])) {
            throw new IllegalArgumentException("Only the existing Compose clamav:3310 is permitted");
        }
        byte[] shallow = nested(1);
        byte[] deep = nested(DEEP_LEVELS);
        verifyNested(shallow, 1);
        verifyNested(deep, DEEP_LEVELS);
        ClamAvClient client = new ClamAvClient("clamav", 3310, 5000, 60000);
        String raw = category(scan(client, EICAR));
        String zip = category(scan(client, shallow));
        System.out.println("raw_eicar=" + raw);
        System.out.println("zip_depth_1=" + zip);
        if (!raw.equals("EICAR_FOUND") || !zip.equals("EICAR_FOUND")) {
            System.out.println("INCONCLUSIVE: positive controls failed; no conclusion about limits.");
            System.exit(2);
        }
        String deepResult = category(scan(client, deep));
        System.out.println("zip_depth_25=" + deepResult + " bytes=" + deep.length);
        if (deepResult.equals("CLEAN")) {
            System.out.println("WARNING: deep archive reported CLEAN despite containing synthetic EICAR; no AV approval.");
            System.exit(3);
        } else if (deepResult.equals("ERROR")) {
            System.out.println("INCONCLUSIVE: deep scan error; no conclusion about completeness.");
            System.exit(2);
        } else {
            System.out.println("Observed detection or limit alert for this one sample only.");
        }
        System.out.println("Not a James gate test, exhaustive limit test, runtime configuration proof or cutover approval.");
    }
}
