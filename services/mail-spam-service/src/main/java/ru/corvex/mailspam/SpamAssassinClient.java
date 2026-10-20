package ru.corvex.mailspam;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Клиент spamd по протоколу SPAMC/1.5 (TCP 783). Сервис — тонкая обёртка вокруг
 * Apache SpamAssassin (контейнер corvex-spamd), собственная эвристика спама не изобретается.
 * <p>Политика при недоступности spamd: <b>fail-open</b> — письмо доставляется с пометкой
 * {@code spamScore = null} и заголовком X-Corvex-Spam-Checked: no, событие уходит в
 * notification-service. Потеря корпоративной почты из-за упавшего фильтра недопустима.
 */
@Component
public class SpamAssassinClient {

    private static final Pattern SPAM_HEADER =
            Pattern.compile("Spam: (True|False) ; ([-\\d.]+) / ([-\\d.]+)");

    private final String host;
    private final int port;

    public SpamAssassinClient(@Value("${corvex.spamd.host:spamd}") String host,
                              @Value("${corvex.spamd.port:783}") int port) {
        this.host = host;
        this.port = port;
    }

    public record Verdict(boolean spam, double score, double threshold, boolean checked) {
        static Verdict unchecked() {
            return new Verdict(false, 0, 5.0, false);
        }
    }

    /** CHECK — быстрая проверка без возврата тела письма. */
    public Verdict check(byte[] eml) {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(("CHECK SPAMC/1.5\r\nContent-length: " + eml.length + "\r\nUser: corvex\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(eml);
            out.flush();
            socket.shutdownOutput();

            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = SPAM_HEADER.matcher(line);
                if (m.find()) {
                    return new Verdict("True".equals(m.group(1)),
                            Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)), true);
                }
            }
            return Verdict.unchecked();
        } catch (IOException e) {
            return Verdict.unchecked(); // fail-open, см. javadoc класса; недоступность фиксируется метрикой и алертом
        }
    }
}
