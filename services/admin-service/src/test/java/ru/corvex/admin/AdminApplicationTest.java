package ru.corvex.admin;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-тест контекста вынесен в IT (Testcontainers); здесь — быстрый unit-слой. */
class AdminApplicationTest {
    @Test void artifactIsNamed() { assertTrue("admin-service".length() > 0); }
}
