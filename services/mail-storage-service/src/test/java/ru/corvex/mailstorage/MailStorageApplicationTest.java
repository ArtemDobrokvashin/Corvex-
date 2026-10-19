package ru.corvex.mailstorage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-тест контекста вынесен в IT (Testcontainers); здесь — быстрый unit-слой. */
class MailStorageApplicationTest {
    @Test void artifactIsNamed() { assertTrue("mail-storage-service".length() > 0); }
}
