package ru.corvex.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same-origin запрос не должен упираться в CORS.
 *
 * <p>Стенд: чистая установка, домен в .env — `mail.company.local`, интерфейс открыт по
 * `https://localhost`. Браузер на POST присылал `Origin: https://localhost`, шлюз считал
 * origin чужим и отвечал `403` пустым телом — вход не работал ни с верным паролем, ни с
 * неверным, и причина нигде не показывалась.
 */
class SameOriginFilterTest {

    private final SameOriginFilter filter = new SameOriginFilter();

    private ServerWebExchange exchangeWith(String origin, String host) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post("/api/v1/auth/login");
        if (origin != null) {
            request.header(HttpHeaders.ORIGIN, origin);
        }
        if (host != null) {
            request.header(HttpHeaders.HOST, host);
        }
        return MockServerWebExchange.from(request.build());
    }

    private ServerWebExchange passThrough(ServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        WebFilterChain chain = current -> {
            seen.set(current);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return seen.get();
    }

    @Test
    void sameOriginHeaderIsRemovedSoCorsDoesNotRejectIt() {
        ServerWebExchange result = passThrough(exchangeWith("https://localhost", "localhost"));

        assertNull(result.getRequest().getHeaders().getFirst(HttpHeaders.ORIGIN),
                "same-origin запрос не должен попадать под проверку списка origin");
    }

    @Test
    void defaultHttpsPortAndCaseDoNotBreakComparison() {
        assertTrue(SameOriginFilter.sameAuthority("https://LOCALHOST:443", "localhost"));
        assertTrue(SameOriginFilter.sameAuthority("http://192.168.1.5:8080", "192.168.1.5:8080"));
    }

    @Test
    void foreignOriginIsKeptAndStillRejectedByCors() {
        ServerWebExchange result = passThrough(exchangeWith("https://evil.example", "localhost"));

        assertEquals("https://evil.example", result.getRequest().getHeaders().getFirst(HttpHeaders.ORIGIN),
                "чужой origin обязан дойти до CORS-фильтра");
    }

    @Test
    void differentPortIsNotSameOrigin() {
        assertFalse(SameOriginFilter.sameAuthority("https://localhost:8443", "localhost"));
    }

    @Test
    void requestWithoutOriginIsUntouched() {
        ServerWebExchange result = passThrough(exchangeWith(null, "localhost"));

        assertNull(result.getRequest().getHeaders().getFirst(HttpHeaders.ORIGIN));
    }

    @Test
    void malformedOriginIsNotTreatedAsSameOrigin() {
        assertFalse(SameOriginFilter.sameAuthority("не-url", "localhost"));
        assertFalse(SameOriginFilter.sameAuthority("", "localhost"));
    }
}
