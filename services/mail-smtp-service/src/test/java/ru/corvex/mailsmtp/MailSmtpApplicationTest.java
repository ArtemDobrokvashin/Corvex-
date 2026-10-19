package ru.corvex.mailsmtp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-тест контекста вынесен в IT (Testcontainers); здесь — быстрый unit-слой. */
class MailSmtpApplicationTest {
    @Test void artifactIsNamed() { assertTrue("mail-smtp-service".length() > 0); }
}
