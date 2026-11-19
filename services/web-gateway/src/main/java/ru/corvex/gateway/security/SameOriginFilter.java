package ru.corvex.gateway.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Locale;

/**
 * Убирает заголовок {@code Origin} у запросов «с того же адреса», на который они пришли.
 *
 * <p>Зачем: список разрешённых origin выводится из {@code CORVEX_DOMAIN}
 * ({@code allowedOriginPatterns: ${CORVEX_WEB_ORIGINS:https://${CORVEX_DOMAIN}}}).
 * На чистой установке домен — {@code mail.company.local}, а интерфейс открывают по
 * {@code https://localhost}. Браузер на каждый POST присылает {@code Origin}, шлюз видит
 * чужой origin и отвечает {@code 403} пустым телом. Снаружи это выглядит как «Доступ
 * запрещён» на форме входа, причём без причины: на стенде так и вышло — вход не работал,
 * хотя адрес верный и пароль верный.
 *
 * <p>CORS по спецификации относится только к чужим origin. Если origin совпадает с
 * адресом запроса, это обычный same-origin запрос: разрешать его по списку не нужно и
 * нельзя — список знает про домен, но не про то, по какому адресу сотрудник реально
 * открыл интерфейс (localhost, LAN-IP, имя машины). Поэтому здесь заголовок снимается
 * до CORS-фильтра, и тот не применяет свои правила.
 *
 * <p>Фильтр стоит первым в цепочке: иначе CORS-фильтр успеет отклонить запрос.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SameOriginFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String origin = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ORIGIN);
        String host = exchange.getRequest().getHeaders().getFirst(HttpHeaders.HOST);
        if (origin == null || host == null || !sameAuthority(origin, host)) {
            return chain.filter(exchange);
        }
        ServerWebExchange sameOrigin = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(headers -> headers.remove(HttpHeaders.ORIGIN))
                        .build())
                .build();
        return chain.filter(sameOrigin);
    }

    /**
     * Сравнивает хост и порт. Схему не сравниваем: за edge-nginx шлюз видит запрос по
     * http, хотя браузер пришёл по https, и такое сравнение всегда давало бы «разные».
     * Дефолтные порты (80/443) отбрасываются, потому что браузер их в Origin не пишет.
     */
    static boolean sameAuthority(String origin, String host) {
        try {
            URI uri = URI.create(origin.trim());
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isBlank()) {
                return false;
            }
            return normalize(authority).equals(normalize(host));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalize(String authority) {
        String value = authority.trim().toLowerCase(Locale.ROOT);
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        if (value.endsWith(":443")) {
            value = value.substring(0, value.length() - 4);
        } else if (value.endsWith(":80")) {
            value = value.substring(0, value.length() - 3);
        }
        return value;
    }
}
