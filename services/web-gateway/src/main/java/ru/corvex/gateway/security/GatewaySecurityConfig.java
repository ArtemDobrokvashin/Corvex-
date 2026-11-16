package ru.corvex.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Gateway — первая линия защиты: проверяет подпись JWT (JWKS auth-service), но НЕ принимает
 * бизнес-решений по правам — точечная авторизация по permissions остаётся в самих сервисах
 * (defense in depth: сервис не доверяет заголовкам слепо, а повторно валидирует токен).
 * <p>Публичны только вход, обновление токена, служебные почтовые autoconfig-эндпоинты,
 * HTTP/SockJS handshake чата и публичные ссылки личного диска
 * ({@code /api/v1/drive/shared/**}). Последние — единственный анонимный путь к файлу:
 * токен проверяет сам chat-file-service на каждый запрос (отзыв, срок, корзина), а gateway
 * не должен обрывать ссылку 401 до этой проверки. Handshake сам по себе доступа к сообщениям не даёт: браузерный
 * WebSocket/SockJS не может поставить Authorization на HTTP Upgrade, поэтому Bearer JWT
 * передаётся в STOMP CONNECT и обязательно проверяется chat-service до подписок и отправок.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain filterChain(ServerHttpSecurity http,
                                        @Value("${corvex.security.jwk-set-uri}") String jwkSetUri) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // API на Bearer-токенах; cookie-refresh защищён SameSite=Strict
                .authorizeExchange(ex -> ex
                        .pathMatchers("/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/mfa/verify", "/api/v1/auth/logout", "/actuator/health/**", "/actuator/prometheus",
                                "/.well-known/autoconfig/**", "/autodiscover/**",
                                // Только handshake: STOMP CONNECT ниже всё равно требует Bearer JWT.
                                "/ws/chat", "/ws/chat/**",
                                // Ссылка «как в Google Drive»: доступ решает chat-file-service,
                                // проверяя хеш токена, отзыв и срок на каждый запрос.
                                "/api/v1/drive/shared/**").permitAll()
                        // Права на /api/v1/admin/** проверяет сам admin-service по claim permissions (SCOPE_admin в токене нет и быть не должно)
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }
}
