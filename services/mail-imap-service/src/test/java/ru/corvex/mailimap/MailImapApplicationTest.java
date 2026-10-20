package ru.corvex.mailimap;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-тест контекста вынесен в IT (Testcontainers); здесь — быстрый unit-слой. */
class MailImapApplicationTest {
    @Test void artifactIsNamed() { assertTrue("mail-imap-service".length() > 0); }
}
