package ru.corvex.mailstorage.contacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Разбор CSV-экспорта контактов (Google Contacts, Outlook, Яндекс, Thunderbird, Roundcube).
 *
 * <p>Особенности реальных файлов, которые здесь учтены: разделитель `,` `;` или табуляция,
 * кавычки с удвоенными кавычками внутри, переводы строк внутри поля, заголовки на русском
 * и английском. Если столбец с адресом не найден, файл не «угадывается»: возвращается
 * ошибка, потому что импорт не тех колонок хуже, чем отказ.
 */
public final class ContactCsvParser {

    public record Result(List<ParsedContact> contacts, List<ImportIssue> issues, int records) {
    }

    private ContactCsvParser() {
    }

    public static Result parse(String text) {
        char delimiter = detectDelimiter(text);
        List<List<String>> records = readRecords(text, delimiter);
        if (records.isEmpty()) {
            return new Result(List.of(), List.of(new ImportIssue(1, "", "файл пуст")), 0);
        }

        List<String> header = records.get(0);
        int emailColumn = emailColumn(header);
        if (emailColumn < 0) {
            return new Result(List.of(),
                    List.of(new ImportIssue(1, String.join(String.valueOf(delimiter), header),
                            "в файле не найден столбец с адресом электронной почты")), 0);
        }
        int nameColumn = nameColumn(header);
        int firstNameColumn = findColumn(header, "first name", "given name", "имя");
        int lastNameColumn = findColumn(header, "last name", "family name", "surname", "фамилия");
        int phoneColumn = findColumn(header, "phone", "tel", "телефон", "мобильн");
        int organizationColumn = findColumn(header, "organization", "company", "организация", "компания");
        int titleColumn = findColumn(header, "job title", "title", "должность");
        int noteColumn = findColumn(header, "note", "заметк", "примечан");
        int groupColumn = findColumn(header, "group", "category", "групп", "категор");

        List<ParsedContact> contacts = new ArrayList<>();
        List<ImportIssue> issues = new ArrayList<>();
        int dataRecords = 0;
        for (int i = 1; i < records.size(); i++) {
            List<String> row = records.get(i);
            if (isBlankRow(row)) {
                continue;
            }
            dataRecords++;
            int line = i + 1;
            String email = cell(row, emailColumn);
            if (email.isBlank()) {
                issues.add(new ImportIssue(line, cell(row, nameColumn >= 0 ? nameColumn : 0),
                        "в строке нет адреса электронной почты"));
                continue;
            }
            String name = cell(row, nameColumn);
            if (name.isBlank()) {
                name = (cell(row, firstNameColumn) + " " + cell(row, lastNameColumn)).trim();
            }
            contacts.add(new ParsedContact(
                    blankToNull(name),
                    email,
                    blankToNull(cell(row, phoneColumn)),
                    blankToNull(cell(row, organizationColumn)),
                    blankToNull(cell(row, titleColumn)),
                    blankToNull(cell(row, noteColumn)),
                    blankToNull(cell(row, groupColumn)),
                    line));
        }
        if (dataRecords == 0) {
            issues.add(new ImportIssue(1, "", "в файле нет строк с контактами"));
        }
        return new Result(contacts, issues, dataRecords);
    }

    /** Разделитель выбирается по первой строке: вне кавычек считается число вхождений. */
    static char detectDelimiter(String text) {
        String firstLine = text.split("\r\n|\n|\r", 2)[0];
        char best = ',';
        int bestCount = 0;
        for (char candidate : new char[]{',', ';', '\t'}) {
            int count = 0;
            boolean quoted = false;
            for (int i = 0; i < firstLine.length(); i++) {
                char c = firstLine.charAt(i);
                if (c == '"') {
                    quoted = !quoted;
                } else if (c == candidate && !quoted) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = candidate;
            }
        }
        return best;
    }

    static List<List<String>> readRecords(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"' && !fieldStarted) {
                quoted = true;
                fieldStarted = true;
            } else if (c == delimiter) {
                row.add(clean(field.toString()));
                field.setLength(0);
                fieldStarted = true;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (fieldStarted || field.length() > 0 || !row.isEmpty()) {
                    row.add(clean(field.toString()));
                    if (!isBlankRow(row)) {
                        records.add(List.copyOf(row));
                    }
                    row = new ArrayList<>();
                }
                field.setLength(0);
                fieldStarted = false;
            } else {
                field.append(c);
                fieldStarted = true;
            }
        }
        if (fieldStarted || !row.isEmpty()) {
            row.add(clean(field.toString()));
            if (!isBlankRow(row)) {
                records.add(List.copyOf(row));
            }
        }
        return records;
    }

    private static int emailColumn(List<String> header) {
        for (int i = 0; i < header.size(); i++) {
            String normalized = normalize(header.get(i));
            if (normalized.contains("value") && (normalized.contains("e-mail") || normalized.contains("email"))) {
                return i;
            }
        }
        return findColumn(header, "e-mail", "email", "mail", "почта", "адрес");
    }

    private static int nameColumn(List<String> header) {
        for (int i = 0; i < header.size(); i++) {
            String normalized = normalize(header.get(i));
            if (normalized.equals("name") || normalized.equals("имя") || normalized.equals("фио")
                    || normalized.equals("full name") || normalized.equals("полное имя")) {
                return i;
            }
        }
        return -1;
    }

    private static int findColumn(List<String> header, String... needles) {
        for (int i = 0; i < header.size(); i++) {
            String normalized = normalize(header.get(i));
            for (String needle : needles) {
                if (normalized.contains(needle)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static String cell(List<String> row, int index) {
        if (index < 0 || index >= row.size()) {
            return "";
        }
        return row.get(index) == null ? "" : row.get(index).trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("\uFEFF", "").trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isBlankRow(List<String> row) {
        for (String value : row) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
