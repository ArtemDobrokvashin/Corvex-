package ru.corvex.mailstorage.migration;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Разбор админского CSV для массового запуска: {@code email, source_host, source_login}.
 *
 * <p>Формат намеренно узкий: паролей в файле нет, поэтому любая лишняя колонка — это
 * ошибка ввода, а не данные, которые нужно угадать. Заголовок необязателен, порядок колонок
 * при заголовке определяется по именам.
 */
public final class MigrationBulkCsv {

    public record Row(String email, String sourceHost, String sourceLogin, int line) {
    }

    public record Issue(int line, String value, String reason) {
    }

    public record Result(List<Row> rows, List<Issue> issues, boolean truncated) {
    }

    public static final int MAX_ROWS = 1000;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private MigrationBulkCsv() {
    }

    public static Result parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new Result(List.of(), List.of(new Issue(0, "", "файл пуст")), false);
        }
        String text = decode(bytes);
        List<String> lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.isEmpty()) {
            return new Result(List.of(), List.of(new Issue(0, "", "файл пуст")), false);
        }
        char delimiter = delimiter(lines.get(0));

        List<Row> rows = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean truncated = false;

        int index = 0;
        int[] columns = {0, 1, 2};
        if (looksLikeHeader(lines.get(0))) {
            columns = headerColumns(split(lines.get(0), delimiter));
            index = 1;
        }
        for (; index < lines.size(); index++) {
            int lineNumber = index + 1;
            List<String> cells = split(lines.get(index), delimiter);
            String email = cell(cells, columns[0]);
            if (email.isEmpty()) {
                issues.add(new Issue(lineNumber, lines.get(index), "не указан адрес"));
                continue;
            }
            if (!EMAIL.matcher(email).matches()) {
                issues.add(new Issue(lineNumber, email, "некорректный адрес"));
                continue;
            }
            String normalized = email.toLowerCase(Locale.ROOT);
            if (!seen.add(normalized)) {
                issues.add(new Issue(lineNumber, email, "адрес повторяется в файле"));
                continue;
            }
            if (rows.size() >= MAX_ROWS) {
                truncated = true;
                break;
            }
            rows.add(new Row(normalized, emptyToNull(cell(cells, columns[1])),
                    emptyToNull(cell(cells, columns[2])), lineNumber));
        }
        return new Result(rows, issues, truncated);
    }

    /** Разделитель определяется по заголовку: в русских выгрузках Excel часто «;». */
    static char delimiter(String header) {
        int comma = count(header, ',');
        int semicolon = count(header, ';');
        int tab = count(header, '\t');
        if (semicolon > comma && semicolon >= tab) return ';';
        if (tab > comma) return '\t';
        return ',';
    }

    static boolean looksLikeHeader(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("email") || lower.contains("e-mail") || lower.contains("почта")
                || lower.contains("адрес");
    }

    private static int[] headerColumns(List<String> header) {
        int[] columns = {0, 1, 2};
        for (int i = 0; i < header.size() && i < 6; i++) {
            String name = header.get(i).toLowerCase(Locale.ROOT).replace("\"", "").strip();
            if (name.equals("email") || name.equals("e-mail") || name.equals("почта") || name.equals("адрес")) {
                columns[0] = i;
            } else if (name.contains("host") || name.contains("сервер") || name.startsWith("imap")) {
                columns[1] = i;
            } else if (name.contains("login") || name.contains("логин") || name.contains("user")) {
                columns[2] = i;
            }
        }
        return columns;
    }

    /** Минимальный CSV: кавычки, удвоенная кавычка внутри и разделитель внутри кавычек. */
    static List<String> split(String line, char delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == delimiter && !quoted) {
                cells.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().strip());
        return cells;
    }

    private static String cell(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index).strip() : "";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int count(String text, char c) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == c) total++;
        return total;
    }

    static String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, java.nio.charset.Charset.forName("windows-1251"));
        }
    }
}
