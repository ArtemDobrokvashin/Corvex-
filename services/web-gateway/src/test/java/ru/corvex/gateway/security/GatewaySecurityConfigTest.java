package ru.corvex.gateway.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewaySecurityConfigTest {

    private GenericApplicationContext context;
    private ReactiveJwtDecoder decoder;
    private SecurityWebFilterChain securityChain;

    @BeforeEach
    void setUp() {
        decoder = mock(ReactiveJwtDecoder.class);
        when(decoder.decode("valid-token")).thenReturn(Mono.just(jwt()));
        context = new GenericApplicationContext();
        context.registerBean(ReactiveJwtDecoder.class, () -> decoder);
        context.refresh();
        securityChain = new GatewaySecurityConfig().filterChain(
                new TestServerHttpSecurity(context),
                "http://auth-service/.well-known/jwks");
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void healthAndLoginEndpointsArePublic() {
        assertEquals(HttpStatus.OK, execute(HttpMethod.GET, "/actuator/health/liveness", null));
        assertEquals(HttpStatus.OK, execute(HttpMethod.POST, "/api/v1/auth/login", null));
        assertEquals(HttpStatus.OK, execute(HttpMethod.GET, "/.well-known/autoconfig/mail/config-v1.1.xml", null));
    }

    @Test
    void chatHandshakeIsPublicAtHttpLayer() {
        // Bearer для SockJS/WebSocket придёт в STOMP CONNECT и будет проверен chat-service;
        // gateway не должен обрывать сам handshake анонимным 401.
        assertEquals(HttpStatus.OK, execute(HttpMethod.GET, "/ws/chat/info", null));
        assertEquals(HttpStatus.OK, execute(HttpMethod.GET, "/ws/chat/123/websocket", null));
    }

    @Test
    void protectedEndpointRejectsAnonymousRequest() {
        assertEquals(HttpStatus.UNAUTHORIZED, execute(HttpMethod.GET, "/api/v1/mail/messages", null));
    }

    @Test
    void protectedEndpointAcceptsBearerJwt() {
        assertEquals(HttpStatus.OK, execute(HttpMethod.GET, "/api/v1/mail/messages", "valid-token"));

        verify(decoder).decode("valid-token");
    }

    private HttpStatusCode execute(HttpMethod method, String path, String bearerToken) {
        MockServerHttpRequest.BaseBuilder<?> requestBuilder = method == HttpMethod.POST
                ? MockServerHttpRequest.post(path)
                : MockServerHttpRequest.get(path);
        if (bearerToken != null) {
            requestBuilder.header("Authorization", "Bearer " + bearerToken);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(requestBuilder.build());
        List<WebFilter> filters = securityChain.getWebFilters().collectList().block();
        AtomicInteger index = new AtomicInteger();
        WebFilterChain chain = new WebFilterChain() {
            @Override
            public Mono<Void> filter(ServerWebExchange currentExchange) {
                int current = index.getAndIncrement();
                if (current < filters.size()) {
                    return filters.get(current).filter(currentExchange, this);
                }
                currentExchange.getResponse().setStatusCode(HttpStatus.OK);
                return Mono.empty();
            }
        };
        chain.filter(exchange).block();
        return exchange.getResponse().getStatusCode();
    }

    private static Jwt jwt() {
        Instant now = Instant.parse("2026-10-23T07:00:00Z");
        return Jwt.withTokenValue("valid-token")
                .header("alg", "RS256")
                .subject("user-1")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("scope", List.of("read"))
                .build();
    }

    private static final class TestServerHttpSecurity extends ServerHttpSecurity {
        private TestServerHttpSecurity(ApplicationContext context) {
            setApplicationContext(context);
        }
    }
}
