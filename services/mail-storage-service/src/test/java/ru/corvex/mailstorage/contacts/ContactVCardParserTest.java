package ru.corvex.mailstorage.contacts;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactVCardParserTest {

    @Test
    void parsesSeveralCardsWithNamesOrganizationAndPhone() {
        String vcf = """
                BEGIN:VCARD
                VERSION:3.0
                FN:Иван Петров
                N:Петров;Иван;;;
                EMAIL;TYPE=INTERNET:ivan@example.ru
                TEL;TYPE=CELL:+7 999 123-45-67
                ORG:ООО Ромашка;Бухгалтерия
                TITLE:Главный бухгалтер
                NOTE:Работает с 2019 года
                CATEGORIES:Бухгалтерия
                END:VCARD
                BEGIN:VCARD
                VERSION:3.0
                EMAIL:petrova@example.ru
                END:VCARD
                """;

        var result = ContactVCardParser.parse(vcf);

        assertEquals(2, result.contacts().size());
        assertEquals(0, result.issues().size());
        ParsedContact first = result.contacts().get(0);
        assertEquals("Иван Петров", first.displayName());
        assertEquals("ivan@example.ru", first.email());
        assertEquals("+7 999 123-45-67", first.phone());
        assertEquals("ООО Ромашка", first.organization());
        assertEquals("Главный бухгалтер", first.jobTitle());
        assertEquals("Работает с 2019 года", first.note());
        assertEquals("Бухгалтерия", first.groupName());
        // Без FN имя собирается из N, а без имени вообще — из локальной части адреса.
        assertEquals("petrova", result.contacts().get(1).displayName());
    }

    @Test
    void unfoldsLongLinesAndUnescapesValues() {
        String vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Очень длинное имя,\r\n  которое перенесено\r\n"
                + "EMAIL:long@example.ru\r\nNOTE:первая строка\\nвторая\\, с запятой\r\nEND:VCARD\r\n";

        var result = ContactVCardParser.parse(vcf);

        assertEquals(1, result.contacts().size());
        assertEquals("Очень длинное имя, которое перенесено", result.contacts().get(0).displayName());
        assertTrue(result.contacts().get(0).note().contains("\n"));
        assertTrue(result.contacts().get(0).note().contains("вторая, с запятой"));
    }

    @Test
    void decodesQuotedPrintableFromOldOutlookExports() {
        // "Иван" в windows-1251: C8 E2 E0 ED
        String vcf = "BEGIN:VCARD\nVERSION:2.1\nFN;CHARSET=windows-1251;ENCODING=QUOTED-PRINTABLE:=C8=E2=E0=ED\n"
                + "EMAIL:ivan@example.ru\nEND:VCARD\n";

        var result = ContactVCardParser.parse(vcf);

        assertEquals(1, result.contacts().size());
        assertEquals("Иван", result.contacts().get(0).displayName());
    }

    @Test
    void reportsCardWithoutEmailInsteadOfDroppingItSilently() {
        String vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:Без адреса\nTEL:+7 111\nEND:VCARD\n";

        var result = ContactVCardParser.parse(vcf);

        assertEquals(0, result.contacts().size());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().get(0).reason().contains("адрес"));
    }

    @Test
    void quotedPrintableDecoderUsesDeclaredCharset() {
        String decoded = ContactVCardParser.decodeQuotedPrintable("=CF=F0=E8=E2=E5=F2",
                Charset.forName("windows-1251"));
        assertEquals("Привет", decoded);
        assertEquals("Hello", ContactVCardParser.decodeQuotedPrintable("Hello", StandardCharsets.UTF_8));
    }
}
