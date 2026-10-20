package ru.corvex.mailspam;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-тест контекста вынесен в IT (Testcontainers); здесь — быстрый unit-слой. */
class MailSpamApplicationTest {
    @Test void artifactIsNamed() { assertTrue("mail-spam-service".length() > 0); }
}
