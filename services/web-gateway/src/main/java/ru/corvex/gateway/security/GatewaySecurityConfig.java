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
 * <p>Публичны только вход, обновление токена и служебные почтовые autoconfig-эндпоинты.
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
                                "/api/v1/auth/mfa/verify", "/actuator/health/**",
                                "/.well-known/autoconfig/**", "/autodiscover/**").permitAll()
                        .pathMatchers("/api/v1/admin/**").hasAuthority("SCOPE_admin")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }
}
