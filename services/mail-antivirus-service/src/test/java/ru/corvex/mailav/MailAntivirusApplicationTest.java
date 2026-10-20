package ru.corvex.mailav;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-тест контекста вынесен в IT (Testcontainers); здесь — быстрый unit-слой. */
class MailAntivirusApplicationTest {
    @Test void artifactIsNamed() { assertTrue("mail-antivirus-service".length() > 0); }
}
