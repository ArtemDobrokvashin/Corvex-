package ru.corvex.gateway.security;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.Objects;

/**
 * KeyResolver-бины, на которые ссылаются SpEL-выражения маршрутов в application.yml
 * (#{@ipKeyResolver} / #{@userKeyResolver}). Без них gateway не создавал маршруты.
 */
@Configuration
public class RateLimitConfig {

    /** Лимиты по IP — для публичных эндпоинтов входа (анти-брутфорс). */
    @Bean
    KeyResolver ipKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                .map(addr -> addr.getAddress().getHostAddress())
                .defaultIfEmpty("unknown-ip");
    }

    /** Лимиты по пользователю из JWT (Principal), для анонимных — по IP. */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .filter(Objects::nonNull)
                .switchIfEmpty(Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                        .map(addr -> addr.getAddress().getHostAddress())
                        .defaultIfEmpty("anonymous"));
    }
}
