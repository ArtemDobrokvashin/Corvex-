package ru.corvex.mailav;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Клиент clamd (TCP 3310), команда INSTREAM: поток передаётся чанками вида
 * [4 байта длины big-endian][данные], терминатор — четыре нулевых байта.
 * Ответы: {@code stream: OK} / {@code stream: <Signature> FOUND} / {@code ... ERROR}.
 * <p>Политика при недоступности clamd — <b>fail-closed</b> (в отличие от спам-фильтра):
 * вложение помечается AV_STATUS=ERROR и блокируется для скачивания до повторной проверки
 * фоновой задачей, письмо при этом доставляется. Базы ClamAV обновляет freshclam в том же контейнере.
 */
@Component
public class ClamAvClient {

    private static final int CHUNK = 64 * 1024;

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
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(60_000);
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
