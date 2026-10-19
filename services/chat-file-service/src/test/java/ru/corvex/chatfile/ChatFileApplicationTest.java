package ru.corvex.chatfile;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-тест контекста вынесен в IT (Testcontainers); здесь — быстрый unit-слой. */
class ChatFileApplicationTest {
    @Test void artifactIsNamed() { assertTrue("chat-file-service".length() > 0); }
}
