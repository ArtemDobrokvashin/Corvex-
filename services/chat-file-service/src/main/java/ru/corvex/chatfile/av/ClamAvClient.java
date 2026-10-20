package ru.corvex.chatfile.av;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Синхронный клиент clamd (INSTREAM, TCP 3310) для проверки вложений до публикации.
 * Тот же протокол использует mail-antivirus-service в почтовом конвейере.
 */
@Component
public class ClamAvClient {

    private static final int CHUNK = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final String host;
    private final int port;

    public ClamAvClient(@Value("${corvex.clamd.host:clamav}") String host,
                        @Value("${corvex.clamd.port:3310}") int port) {
        this.host = host;
        this.port = port;
    }

    public enum Result {CLEAN, INFECTED, ERROR}

    public record ScanResult(Result result, String signature) {
    }

    public ScanResult scan(InputStream in) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            var out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.writeInt(n);
                out.write(buf, 0, n);
            }
            out.writeInt(0);
            out.flush();

            String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
            if (reply.endsWith("OK")) {
                return new ScanResult(Result.CLEAN, null);
            }
            if (reply.contains("FOUND")) {
                String signature = reply.replace("stream:", "").replace("FOUND", "").trim();
                return new ScanResult(Result.INFECTED, signature);
            }
            return new ScanResult(Result.ERROR, reply);
        } catch (IOException e) {
            return new ScanResult(Result.ERROR, e.getMessage());
        }
    }
}
