package ru.corvex.gateway.security;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

/**
 * KeyResolver-бины, на которые ссылаются SpEL-выражения маршрутов в application.yml
 * (#{@ipKeyResolver} / #{@userKeyResolver}). Без них gateway не создавал маршруты.
 */
@Configuration
public class RateLimitConfig {

    /** Лимиты по IP — для публичных эндпоинтов входа (анти-брутфорс). */
    @Bean
    KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(clientIp(exchange.getRequest()));
    }

    /** Лимиты по пользователю из JWT (Principal), для анонимных — по IP. */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .flatMap(principal -> Mono.justOrEmpty(principal.getName()))
                .switchIfEmpty(Mono.just(clientIp(exchange.getRequest())));
    }

    /**
     * За nginx remoteAddress — адрес самого nginx, а не сотрудника. Доверяем
     * X-Real-IP, который edge-nginx перезаписывает из TCP-соединения, затем
     * последнему элементу X-Forwarded-For и только потом прямому адресу.
     * Первый элемент XFF клиент может подделать, поэтому его не используем.
     */
    private static String clientIp(ServerHttpRequest request) {
        String realIp = request.getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] parts = forwardedFor.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = parts[i].trim();
                if (!candidate.isBlank()) {
                    return candidate;
                }
            }
        }
        return request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress()
                : "unknown-ip";
    }
}
