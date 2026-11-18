package ru.corvex.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayPropertiesPrefixTest {

    @Test
    void routesAndDefaultFiltersUseGatewayServerWebfluxPrefix() throws Exception {
        StandardEnvironment environment = prefixEnvironment();

        String prefix = "spring.cloud.gateway.server.webflux";
        // Маршруты ищем по id, а не по номеру: порядок в списке меняется каждый раз,
        // когда добавляют новый сервис (так уже случилось с onboarding и calendar),
        // и проверка "routes[3]" падала не из-за регрессии, а из-за сдвига индексов.
        Map<String, IndexedRoute> routes = routesById(environment, prefix);
        assertTrue(routes.containsKey("auth"));
        assertTrue(routes.containsKey("admin"));

        IndexedRoute chatWs = routes.get("chat-ws");
        assertNotNull(chatWs, "маршрут chat-ws должен остаться в списке");
        // http:// здесь принципиален: Gateway превратит Upgrade в ws://, а SockJS /info
        // и HTTP-fallback пойдут обычным HTTP-прокси. ws:// ломал /info ответом 400.
        assertEquals("http://chat-service:8088",
                environment.getProperty(prefix + ".routes[" + chatWs.index() + "].uri"));
        assertEquals("Path=/ws/chat/**",
                environment.getProperty(prefix + ".routes[" + chatWs.index() + "].predicates[0]"));
        assertTrue(environment.getProperty(prefix + ".default-filters[0]")
                .startsWith("AddRequestHeader=X-Request-Id"));
        assertNull(environment.getProperty("spring.cloud.gateway.routes[0].id"));
    }

    private static StandardEnvironment prefixEnvironment() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("gateway", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    private static Map<String, IndexedRoute> routesById(StandardEnvironment environment, String prefix) {
        Map<String, IndexedRoute> routes = new LinkedHashMap<>();
        for (int index = 0; ; index++) {
            String id = environment.getProperty(prefix + ".routes[" + index + "].id");
            if (id == null) {
                return routes;
            }
            routes.put(id, new IndexedRoute(index));
        }
    }

    private record IndexedRoute(int index) {
    }
}
