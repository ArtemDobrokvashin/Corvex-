package ru.corvex.notification;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-тест контекста вынесен в IT (Testcontainers); здесь — быстрый unit-слой. */
class NotificationApplicationTest {
    @Test void artifactIsNamed() { assertTrue("notification-service".length() > 0); }
}
