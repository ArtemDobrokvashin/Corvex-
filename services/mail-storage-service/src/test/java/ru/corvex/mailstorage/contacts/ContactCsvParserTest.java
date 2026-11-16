package ru.corvex.mailstorage.contacts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactCsvParserTest {

    @Test
    void parsesGoogleContactsHeader() {
        String csv = """
                Name,Given Name,Family Name,E-mail 1 - Value,Phone 1 - Value,Organization 1 - Name
                Иван Петров,Иван,Петров,ivan@example.ru,+7 999 000-00-00,Ромашка
                """;

        var result = ContactCsvParser.parse(csv);

        assertEquals(1, result.contacts().size());
        ParsedContact contact = result.contacts().get(0);
        assertEquals("Иван Петров", contact.displayName());
        assertEquals("ivan@example.ru", contact.email());
        assertEquals("Ромашка", contact.organization());
        assertEquals(2, contact.line());
    }

    @Test
    void parsesSemicolonDelimitedOutlookExportWithQuotedCommas() {
        String csv = "First Name;Last Name;E-mail Address;Company\n"
                + "Анна;Сидорова;anna@example.ru;\"ООО Ромашка, отдел продаж\"\n";

        var result = ContactCsvParser.parse(csv);

        assertEquals(1, result.contacts().size());
        assertEquals("Анна Сидорова", result.contacts().get(0).displayName());
        assertEquals("ООО Ромашка, отдел продаж", result.contacts().get(0).organization());
    }

    @Test
    void reportsRowsWithoutEmailAndSkipsBlankLines() {
        String csv = "Name,E-mail\nБез адреса,\n\nПётр,petr@example.ru\n";

        var result = ContactCsvParser.parse(csv);

        assertEquals(1, result.contacts().size());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().get(0).reason().contains("нет адреса"));
        assertEquals(2, result.issues().get(0).line());
    }

    @Test
    void refusesFileWithoutEmailColumn() {
        var result = ContactCsvParser.parse("Имя,Телефон\nИван,+7 999\n");

        assertEquals(0, result.contacts().size());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().get(0).reason().contains("столбец"));
    }

    @Test
    void handlesMultiLineQuotedField() {
        String csv = "Name,E-mail,Notes\nИван,ivan@example.ru,\"Строка один\nСтрока два\"\n";

        var result = ContactCsvParser.parse(csv);

        assertEquals(1, result.contacts().size());
        assertTrue(result.contacts().get(0).note().contains("Строка два"));
    }

    @Test
    void detectsTabDelimiter() {
        var result = ContactCsvParser.parse("Name\tE-mail\nИван\tivan@example.ru\n");

        assertEquals(1, result.contacts().size());
        assertEquals("ivan@example.ru", result.contacts().get(0).email());
    }
}
