package ru.corvex.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayPropertiesPrefixTest {

    @Test
    void routesAndDefaultFiltersUseGatewayServerWebfluxPrefix() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("gateway", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);

        String prefix = "spring.cloud.gateway.server.webflux";
        assertEquals("auth", environment.getProperty(prefix + ".routes[0].id"));
        assertEquals("chat-ws", environment.getProperty(prefix + ".routes[3].id"));
        // http:// здесь принципиален: Gateway превратит Upgrade в ws://, а SockJS /info
        // и HTTP-fallback пойдут обычным HTTP-прокси. ws:// ломал /info ответом 400.
        assertEquals("http://chat-service:8088", environment.getProperty(prefix + ".routes[3].uri"));
        assertEquals("Path=/ws/chat/**", environment.getProperty(prefix + ".routes[3].predicates[0]"));
        assertEquals("admin", environment.getProperty(prefix + ".routes[5].id"));
        assertTrue(environment.getProperty(prefix + ".default-filters[0]")
                .startsWith("AddRequestHeader=X-Request-Id"));
        assertNull(environment.getProperty("spring.cloud.gateway.routes[0].id"));
    }
}
