package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ru.corvex.mailstorage.security.InternalTokenGuard;

/** Internal admin-service RBAC query, disconnected until configuration and TLS are reviewed. */
final class AdminMailPermissionClient implements CurrentMailPermissionLookup {
    private final URI base;
    private final ObjectMapper mapper;
    private final InternalTokenGuard tokens;
    private final HttpClient client;
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    AdminMailPermissionClient(URI base, ObjectMapper mapper, InternalTokenGuard tokens) {
        Objects.requireNonNull(base);
        if ((!"http".equals(base.getScheme()) && !"https".equals(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
            throw new IllegalArgumentException("Invalid admin service origin");
        }
        this.base = base;
        this.mapper = Objects.requireNonNull(mapper);
        this.tokens = Objects.requireNonNull(tokens);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public boolean canReadOwnMail(UUID ownerId) {
        Objects.requireNonNull(ownerId);
        URI uri = base.resolve("/internal/users/" + ownerId + "/access");
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header(InternalTokenGuard.HEADER, tokens.tokenOrFail())
                .header("Accept", "application/json").GET().build();
        var future = client.sendAsync(req, info -> new JamesJmapReadClient.BoundedBodySubscriber());
        try {
            HttpResponse<byte[]> response = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new IllegalStateException("Admin access lookup failed");
            JsonNode body = mapper.readTree(response.body());
            if (body == null || !body.isObject() || !body.path("permissions").isArray()) {
                throw new IllegalStateException("Invalid admin access response");
            }
            boolean granted = false;
            for (JsonNode permission : body.path("permissions")) {
                if (!permission.isTextual()) throw new IllegalStateException("Invalid admin permission");
                if ("MAIL_READ_OWN".equals(permission.textValue())) granted = true;
            }
            return granted;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Admin access lookup interrupted", e);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("Admin access lookup timed out", e);
        } catch (ExecutionException | java.io.IOException e) {
            throw new IllegalStateException("Admin access lookup failed", e);
        }
    }
}
