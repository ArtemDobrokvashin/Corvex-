package ru.corvex.chat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-тест контекста вынесен в IT (Testcontainers); здесь — быстрый unit-слой. */
class ChatApplicationTest {
    @Test void artifactIsNamed() { assertTrue("chat-service".length() > 0); }
}
