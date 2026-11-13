package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * DISCONNECTED experimental transport; no Spring bean or endpoint. It must not
 * be enabled until the dedicated WebAdmin network, TLS, key management and
 * caller authorization are reviewed. WebAdmin has administrator authority.
 * Only creation, no force/upsert and no user-supplied URL or login. A failed
 * or ambiguous response is NEVER evidence that no account was created.
 */
final class JamesWebAdminCreateClient implements AutoCloseable {
    private final URI origin;
    private final String administratorSecret;
    private final ObjectMapper mapper;
    private final CloseableHttpClient http;

    JamesWebAdminCreateClient(URI origin, String administratorSecret, ObjectMapper mapper, SSLContext tls) {
        this(origin, administratorSecret, mapper, tls, false);
    }

    /** Loopback HTTP is permitted only for unit tests, never via configuration. */
    JamesWebAdminCreateClient(URI origin, String administratorSecret, ObjectMapper mapper,
                              SSLContext tls, boolean loopbackTest) {
        Objects.requireNonNull(origin);
        boolean secure = "https".equalsIgnoreCase(origin.getScheme());
        boolean test = loopbackTest && "http".equalsIgnoreCase(origin.getScheme()) && "127.0.0.1".equals(origin.getHost());
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
        this.mapper = Objects.requireNonNull(mapper);
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                        .setSslContext(Objects.requireNonNull(tls)).build())
                .build();
        var config = RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(3))
                .setConnectTimeout(Timeout.ofSeconds(3)).setResponseTimeout(Timeout.ofSeconds(10)).build();
        this.http = HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(config)
                .disableAutomaticRetries().disableRedirectHandling().disableContentCompression()
                .disableCookieManagement().disableAuthCaching().build();
    }

    /**
     * Return true ONLY for James' documented 204. Every other status, transport
     * error or timeout is uncertain; never log the response, URL, password or
     * body. The caller must persist intent before this call and block retries.
     */
    boolean create(String generatedLogin, String generatedPassword) throws IOException {
        if (generatedLogin == null || !generatedLogin.matches("cvx-[0-9a-f]{32}@localhost")) {
            throw new IllegalArgumentException("Invalid generated login");
        }
        if (generatedPassword == null || !generatedPassword.matches("[A-Za-z0-9_-]{32,256}")) {
            throw new IllegalArgumentException("Invalid generated account password");
        }
        var request = new HttpPut(origin.resolve("/users/" + generatedLogin));
        request.setHeader("Password", administratorSecret);
        byte[] body = mapper.writeValueAsBytes(Map.of("password", generatedPassword));
        request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        try (var response = http.executeOpen(null, request, null)) {
            // Do not drain or parse an arbitrary administrative error body.
            int code = response.getCode();
            request.cancel();
            return code == 204;
        } catch (IOException e) {
            // Do not expose a potentially sensitive upstream exception/body.
            throw new IOException("James account creation outcome uncertain");
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
        }
    }

    @Override public void close() throws IOException { http.close(); }
}
