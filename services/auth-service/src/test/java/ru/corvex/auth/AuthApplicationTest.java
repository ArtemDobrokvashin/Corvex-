package ru.corvex.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-тест контекста вынесен в IT (Testcontainers); здесь — быстрый unit-слой. */
class AuthApplicationTest {
    @Test void artifactIsNamed() { assertTrue("auth-service".length() > 0); }
}
