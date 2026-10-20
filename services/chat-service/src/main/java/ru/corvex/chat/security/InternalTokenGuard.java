package ru.corvex.chat.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Fail-closed guard для internal API chat-service.
 * Пустой секрет даёт 503, неверный заголовок — 401; сравнение выполняется constant-time.
 */
@Component
public class InternalTokenGuard {

    public static final String HEADER = "X-Corvex-Internal-Token";

    private final String expected;

    public InternalTokenGuard(@Value("${corvex.security.internal-token:${CORVEX_INTERNAL_API_TOKEN:}}") String expected) {
        this.expected = expected;
    }

    public void verify(String actual) {
        if (expected == null || expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "CORVEX_INTERNAL_API_TOKEN не настроен");
        }
        if (actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal token");
        }
    }

    public String tokenOrFail() {
        if (expected == null || expected.isBlank()) {
            throw new IllegalStateException("CORVEX_INTERNAL_API_TOKEN не настроен");
        }
        return expected;
    }
}
