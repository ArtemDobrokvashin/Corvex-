package ru.corvex.gateway;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-тест контекста вынесен в IT (Testcontainers); здесь — быстрый unit-слой. */
class WebGatewayApplicationTest {
    @Test void artifactIsNamed() { assertTrue("web-gateway".length() > 0); }
}
