package ru.corvex.mailstorage.james;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.util.Timeout;
import ru.corvex.mailstorage.maildomain.MailDomainName;

/**
 * Клиент доменов James WebAdmin через выделенный mTLS-мост.
 *
 * <p>Как и клиент создания пользователей, не повторяет запросы, не следует
 * редиректам и не логирует ответы. Разрешены только два пути:
 * {@code PUT /domains/{domain}} и {@code GET /domains/{domain}}, без query и
 * force. 204 на PUT означает «домен присутствует в James» (James так отвечает и
 * на повторное добавление), 400 — отказ; GET 204/404 позволяет достоверно
 * разрешить неизвестный исход.
 */
public final class JamesDomainAdminClient implements JamesDomainAdmin, AutoCloseable {

    private final URI origin;
    private final String administratorSecret;
    private final CloseableHttpClient http;

    JamesDomainAdminClient(URI origin, String administratorSecret, SSLContext tls) {
        this(origin, administratorSecret, tls, false);
    }

    /** Loopback HTTP разрешён только для модульных тестов, не через конфигурацию. */
    JamesDomainAdminClient(URI origin, String administratorSecret, SSLContext tls, boolean loopbackTest) {
        Objects.requireNonNull(origin);
        boolean secure = "https".equalsIgnoreCase(origin.getScheme());
        boolean test = loopbackTest && "http".equalsIgnoreCase(origin.getScheme())
                && "127.0.0.1".equals(origin.getHost());
        if ((!secure && !test) || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))) {
            throw new IllegalArgumentException("Invalid WebAdmin origin");
        }
        if (administratorSecret == null || !administratorSecret.matches("[A-Za-z0-9_-]{32,256}")) {
            throw new IllegalArgumentException("Invalid dedicated WebAdmin secret");
        }
        this.origin = origin;
        this.administratorSecret = administratorSecret;
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                        .setSslContext(Objects.requireNonNull(tls)).build())
                .build();
        var config = RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(3))
                .setConnectTimeout(Timeout.ofSeconds(3)).setResponseTimeout(Timeout.ofSeconds(10))
                .setAuthenticationEnabled(false).build();
        this.http = HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(config)
                .disableAutomaticRetries().disableRedirectHandling().disableContentCompression()
                .disableCookieManagement().disableAuthCaching().build();
    }

    @Override
    public CreateOutcome create(String domain) {
        String target = path(domain);
        try {
            int code = execute(new HttpPut(origin.resolve(target))).code();
            if (code == 204) {
                return CreateOutcome.PRESENT;
            }
            if (code == 400) {
                return CreateOutcome.REJECTED;
            }
            return CreateOutcome.UNCERTAIN;
        } catch (IOException | RuntimeException e) {
            return CreateOutcome.UNCERTAIN;
        }
    }

    @Override
    public Presence presence(String domain) {
        String target = path(domain);
        try {
            int code = execute(new HttpGet(origin.resolve(target))).code();
            if (code == 204) {
                return Presence.PRESENT;
            }
            if (code == 404) {
                return Presence.ABSENT;
            }
            return Presence.UNKNOWN;
        } catch (IOException | RuntimeException e) {
            return Presence.UNKNOWN;
        }
    }

    private static String path(String domain) {
        String normalized = MailDomainName.normalize(domain);
        return "/domains/" + normalized;
    }

    private Response execute(org.apache.hc.core5.http.ClassicHttpRequest request) throws IOException {
        if (org.slf4j.LoggerFactory.getLogger("org.apache.hc.client5.http.wire").isDebugEnabled()
                || org.slf4j.LoggerFactory.getLogger("org.apache.hc.client5.http.headers").isDebugEnabled()) {
            throw new IOException("Unsafe HTTP debug logging enabled");
        }
        request.setHeader("Password", administratorSecret);
        var executor = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "james-domain-request");
            thread.setDaemon(true);
            return thread;
        });
        var future = executor.submit(() -> {
            try (var response = http.executeOpen(null, request, null)) {
                int code = response.getCode();
                request.cancel();
                return new Response(code);
            }
        });
        try {
            // Общий предел, включая медленно присылаемые заголовки ответа.
            return future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("James domain operation outcome uncertain");
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("James domain operation outcome uncertain");
        } finally {
            request.cancel();
            future.cancel(true);
            executor.shutdownNow();
        }
    }

    private record Response(int code) { }

    @Override
    public void close() throws IOException {
        http.close();
    }
}
