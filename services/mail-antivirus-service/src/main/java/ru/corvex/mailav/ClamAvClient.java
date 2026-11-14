package ru.corvex.mailav;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Клиент clamd (TCP 3310), команда zINSTREAM: поток передаётся чанками вида
 * [4 байта длины big-endian][данные], терминатор — четыре нулевых байта.
 * Команда отправляется как {@code zINSTREAM\0}; ответ clamd — одна строка, завершённая
 * NUL-байтом (в стиле z-фрейминга): {@code stream: OK\0} /
 * {@code stream: <Сигнатура> FOUND\0} / {@code stream: <Описание> ERROR\0}.
 * <p>Распознавание ответа строгое: чистый результат подтверждается только точным
 * {@code stream: OK} без пробелов и иных символов вокруг; заражение — только
 * {@code stream: <сигнатура> FOUND}; любой некорректный, пустой, усечённый,
 * недотерминированный (без завершающего NUL) или слишком длинный ответ —
 * {@link Result#ERROR}. Чтение ответа ограничено по размеру, чтобы не исчерпать память
 * на некорректном ответе.
 * <p>Выходной поток сокета не закрывается до чтения ответа — сокет закрывается после
 * завершения обмена.
 * <p>Политика при недоступности clamd — <b>fail-closed</b> (в отличие от спам-фильтра):
 * вложение помечается AV_STATUS=ERROR и блокируется для скачивания до повторной проверки
 * фоновой задачей, письмо при этом доставляется. Базы ClamAV обновляет freshclam в том же контейнере.
 */
@Component
public class ClamAvClient {

    private static final int CHUNK = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    /** clamd отвечает одной короткой NUL-терминированной строкой; лимит защищает от исчерпания памяти. */
    private static final int MAX_REPLY_SIZE = 1_024 * 1_024;
    /** clamd завершает ответ на zINSTREAM NUL-байтом. */
    private static final char NUL = '\0';
    /** read() == 0 более 100 раз подряд считается застывшим потоком. */
    private static final int MAX_CONSECUTIVE_ZERO_READS = 100;

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    @Autowired
    public ClamAvClient(@Value("${corvex.clamd.host:clamav}") String host,
                        @Value("${corvex.clamd.port:3310}") int port) {
        this(host, port, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    /** Конструктор для тестов и специальных конфигураций с настраиваемыми таймаутами. */
    public ClamAvClient(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public enum Result {CLEAN, INFECTED, ERROR}

    public record ScanResult(Result result, String signature) {
    }

    public ScanResult scan(InputStream in) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            var out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));

            // Читаем весь входной поток до конца. read() возвращает -1 только при
            // достижении конца потока. read() == 0 означает "нет данных сейчас",
            // но если это повторяется много раз подряд — поток застрял, и мы
            // безопасно завершаем сканирование с ERROR, чтобы не уйти в бесконечный цикл.
            byte[] buf = new byte[CHUNK];
            int n;
            int consecutiveZeros = 0;
            while (true) {
                n = in.read(buf);
                if (n < 0) {
                    break; // конец потока — всё отправлено
                }
                if (n == 0) {
                    consecutiveZeros++;
                    if (consecutiveZeros > MAX_CONSECUTIVE_ZERO_READS) {
                        return new ScanResult(Result.ERROR,
                                "входной поток не передаёт данные (read() == 0)");
                    }
                    continue; // не конец потока, пробуем ещё
                }
                consecutiveZeros = 0;
                out.writeInt(n);
                out.write(buf, 0, n);
            }
            out.writeInt(0);
            out.flush();

            String rawReply = readReply(socket.getInputStream());
            return parseReply(rawReply);
        } catch (IOException e) {
            return new ScanResult(Result.ERROR, e.getMessage());
        }
    }

    /**
     * Читает ответ clamd до завершающего NUL, закрытия соединения или лимита размера.
     * clamd всегда NUL-терминирует ответы на z-команды, поэтому отсутствие NUL означает
     * усечённый/повреждённый ответ.
     * Возвращает сырые байты ответа (включая NUL, если он был), без обрезки.
     */
    private static String readReply(InputStream input) throws IOException {
        try (BufferedInputStream bin = new BufferedInputStream(input)) {
            StringBuilder sb = new StringBuilder();
            int remaining = MAX_REPLY_SIZE;
            int b;
            while ((b = bin.read()) != -1 && remaining-- > 0) {
                char ch = (char) b;
                sb.append(ch);
                if (ch == NUL) {
                    break; // ответ clamd — одна строка, завершённая NUL
                }
            }
            return sb.toString();
        }
    }

    private static ScanResult parseReply(String rawReply) {
        boolean terminated = rawReply.endsWith("\0");
        String reply = terminated ? rawReply.substring(0, rawReply.length() - 1) : rawReply;

        if (reply.isEmpty()) {
            return new ScanResult(Result.ERROR, "пустой ответ clamd");
        }
        if (!terminated) {
            return new ScanResult(Result.ERROR, "ответ clamd без завершающего NUL: " + reply);
        }
        if ("stream: OK".equals(reply)) {
            return new ScanResult(Result.CLEAN, null);
        }
        if (reply.startsWith("stream: ") && reply.endsWith(" FOUND")) {
            String signature = reply.substring("stream: ".length(), reply.length() - " FOUND".length());
            return new ScanResult(Result.INFECTED, signature);
        }
        return new ScanResult(Result.ERROR, reply);
    }
}
