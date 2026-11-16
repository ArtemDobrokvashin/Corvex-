package ru.corvex.mailstorage.contacts;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactImportParserTest {

    @Test
    void detectsVCardByContentWhenExtensionIsMissing() {
        byte[] bytes = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Иван\r\nEMAIL:ivan@example.ru\r\nEND:VCARD\r\n"
                .getBytes(StandardCharsets.UTF_8);

        var result = ContactImportParser.parse("contacts.txt", bytes, ContactImportParser.Format.AUTO);

        assertEquals(ContactImportParser.Format.VCARD, result.format());
        assertEquals(1, result.contacts().size());
    }

    @Test
    void decodesUtf8WithBom() {
        byte[] body = "Name,E-mail\nПётр,petr@example.ru\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);

        var result = ContactImportParser.parse("contacts.csv", bytes, ContactImportParser.Format.AUTO);

        assertEquals(1, result.contacts().size());
        assertEquals("Пётр", result.contacts().get(0).displayName());
    }

    @Test
    void fallsBackToWindows1251ForOldExports() {
        byte[] bytes = "Имя,E-mail\nИван,ivan@example.ru\n".getBytes(Charset.forName("windows-1251"));

        var result = ContactImportParser.parse("old.csv", bytes, ContactImportParser.Format.CSV);

        assertEquals(1, result.contacts().size());
        assertEquals("Иван", result.contacts().get(0).displayName());
    }

    @Test
    void decodesUtf16WithBom() {
        byte[] body = "Name,E-mail\nАнна,anna@example.ru\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[body.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(body, 0, bytes, 2, body.length);

        var result = ContactImportParser.parse("contacts.csv", bytes, ContactImportParser.Format.AUTO);

        assertEquals(1, result.contacts().size());
        assertEquals("Анна", result.contacts().get(0).displayName());
    }

    @Test
    void explicitFormatWinsOverExtension() {
        byte[] csv = "Name,E-mail\nИван,ivan@example.ru\n".getBytes(StandardCharsets.UTF_8);

        var result = ContactImportParser.parse("export.vcf", csv, ContactImportParser.Format.CSV);

        assertEquals(ContactImportParser.Format.CSV, result.format());
        assertEquals(1, result.contacts().size());
    }

    @Test
    void detectsCsvByExtension() {
        assertEquals(ContactImportParser.Format.CSV,
                ContactImportParser.detect("contacts.csv", new byte[0]));
        assertEquals(ContactImportParser.Format.VCARD,
                ContactImportParser.detect("contacts.vcf", new byte[0]));
        assertTrue(ContactImportParser.detect("contacts", "BEGIN:VCARD".getBytes(StandardCharsets.UTF_8))
                == ContactImportParser.Format.VCARD);
    }
}
