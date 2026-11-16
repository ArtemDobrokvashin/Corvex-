package ru.corvex.mailstorage.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationBulkCsvTest {

    @Test
    void readsHeaderColumnsInAnyOrder() {
        var result = MigrationBulkCsv.parse("""
                source_host,email,source_login
                imap.old.ru,Ivan@Old.ru,ivan
                """.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, result.rows().size());
        assertEquals("ivan@old.ru", result.rows().get(0).email());
        assertEquals("imap.old.ru", result.rows().get(0).sourceHost());
        assertEquals("ivan", result.rows().get(0).sourceLogin());
    }

    @Test
    void acceptsSemicolonDelimiterAndUtf8Bom() {
        byte[] body = "email;source_host;source_login\nanna@company.ru;imap.yandex.ru;anna\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        var result = MigrationBulkCsv.parse(withBom);

        assertEquals(1, result.rows().size());
        assertEquals("anna@company.ru", result.rows().get(0).email());
    }

    @Test
    void decodesWindows1251FromOldExcel() {
        byte[] bytes = "email,source_host\nпётр@company.ru,mail.old.ru\n"
                .getBytes(Charset.forName("windows-1251"));

        var result = MigrationBulkCsv.parse(bytes);

        assertEquals("пётр@company.ru", result.rows().get(0).email());
    }

    @Test
    void reportsInvalidAndDuplicateAddressesWithoutStopping() {
        var result = MigrationBulkCsv.parse("""
                email,source_host
                ivan@old.ru,imap.old.ru
                ivan@old.ru,imap.old.ru
                without-at-sign,imap.old.ru
                """.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, result.rows().size());
        assertEquals(2, result.issues().size());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.reason().contains("повторяется")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.reason().contains("некорректный")));
    }

    @Test
    void optionalColumnsMayBeMissing() {
        var result = MigrationBulkCsv.parse("ivan@old.ru\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, result.rows().size());
        assertNull(result.rows().get(0).sourceHost());
        assertNull(result.rows().get(0).sourceLogin());
        assertFalse(result.truncated());
    }

    @Test
    void quotedFieldMayContainDelimiter() {
        var result = MigrationBulkCsv.parse((
                "email,source_host,source_login\n"
                        + "\"ivan@old.ru\",\"imap.old.ru\",\"ivan,petrov\"\n")
                .getBytes(StandardCharsets.UTF_8));

        assertEquals(1, result.rows().size());
        assertEquals("ivan,petrov", result.rows().get(0).sourceLogin());
    }

    @Test
    void emptyFileIsAnIssueNotACrash() {
        var result = MigrationBulkCsv.parse(new byte[0]);

        assertTrue(result.rows().isEmpty());
        assertEquals(1, result.issues().size());
    }

    @Test
    void stopsAfterOneThousandRows() {
        StringBuilder csv = new StringBuilder("email,source_host\n");
        for (int i = 0; i < 1005; i++) {
            csv.append("user").append(i).append("@old.ru,imap.old.ru\n");
        }

        var result = MigrationBulkCsv.parse(csv.toString().getBytes(StandardCharsets.UTF_8));

        assertEquals(MigrationBulkCsv.MAX_ROWS, result.rows().size());
        assertTrue(result.truncated());
    }
}
