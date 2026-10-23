package ru.corvex.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import java.net.InetSocketAddress;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitConfigTest {

    private final RateLimitConfig config = new RateLimitConfig();

    @Test
    void ipResolverPrefersTrimmedRealIpHeader() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api")
                .remoteAddress(address("192.0.2.20"))
                .header("X-Real-IP", " 203.0.113.9 ")
                .header("X-Forwarded-For", "198.51.100.1"));

        assertEquals("203.0.113.9", resolve(config.ipKeyResolver(), exchange));
    }

    @Test
    void ipResolverUsesLastNonBlankForwardedForValueWhenRealIpIsBlank() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api")
                .remoteAddress(address("192.0.2.20"))
                .header("X-Real-IP", " ")
                .header("X-Forwarded-For", "198.51.100.1, 203.0.113.7, "));

        assertEquals("203.0.113.7", resolve(config.ipKeyResolver(), exchange));
    }

    @Test
    void ipResolverFallsBackToRemoteAddressWhenProxyHeadersHaveNoUsableValue() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api")
                .remoteAddress(address("192.0.2.20"))
                .header("X-Forwarded-For", " , ,"));

        assertEquals("192.0.2.20", resolve(config.ipKeyResolver(), exchange));
    }

    @Test
    void ipResolverUsesStableUnknownKeyWithoutRemoteAddress() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api"));

        assertEquals("unknown-ip", resolve(config.ipKeyResolver(), exchange));
    }

    @Test
    void userResolverUsesPrincipalName() {
        Principal principal = () -> "user-42";
        MockServerWebExchange exchange = MockServerWebExchange
                .builder(MockServerHttpRequest.get("/api")
                        .remoteAddress(address("192.0.2.20")))
                .principal(principal)
                .build();

        assertEquals("user-42", resolve(config.userKeyResolver(), exchange));
    }

    @Test
    void userResolverFallsBackToClientIpForAnonymousRequest() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api")
                .header("X-Real-IP", "203.0.113.11"));

        assertEquals("203.0.113.11", resolve(config.userKeyResolver(), exchange));
    }

    private static MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> builder) {
        return MockServerWebExchange.from(builder.build());
    }

    private static InetSocketAddress address(String ip) {
        return new InetSocketAddress(ip, 443);
    }

    private static String resolve(KeyResolver resolver, MockServerWebExchange exchange) {
        return resolver.resolve(exchange).block();
    }
}
