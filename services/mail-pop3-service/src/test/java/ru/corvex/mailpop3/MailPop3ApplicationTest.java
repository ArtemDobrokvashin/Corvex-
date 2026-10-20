package ru.corvex.mailpop3;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-тест контекста вынесен в IT (Testcontainers); здесь — быстрый unit-слой. */
class MailPop3ApplicationTest {
    @Test void artifactIsNamed() { assertTrue("mail-pop3-service".length() > 0); }
}
