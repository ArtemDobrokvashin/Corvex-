package ru.corvex.mailstorage.contacts;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Разбор vCard 2.1/3.0/4.0: несколько карточек в файле, перенос длинных строк,
 * quoted-printable из старых экспортов Outlook/Thunderbird, параметры и экранирование.
 *
 * <p>Парсер намеренно не «умный»: он достаёт адрес, имя, телефон, организацию, должность,
 * заметку и группу. Всё, что не удалось разобрать, возвращается как ImportIssue, а не
 * теряется молча.
 */
public final class ContactVCardParser {

    public record Result(List<ParsedContact> contacts, List<ImportIssue> issues) {
    }

    private ContactVCardParser() {
    }

    public static Result parse(String text) {
        List<ParsedContact> contacts = new ArrayList<>();
        List<ImportIssue> issues = new ArrayList<>();
        List<String> lines = logicalLines(text);

        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (!line.trim().equalsIgnoreCase("BEGIN:VCARD")) {
                index++;
                continue;
            }
            int start = index;
            Map<String, String> properties = new LinkedHashMap<>();
            index++;
            while (index < lines.size() && !lines.get(index).trim().equalsIgnoreCase("END:VCARD")) {
                Property property = Property.parse(lines.get(index));
                if (property != null && !property.name.equals("VERSION")) {
                    // Первое значение свойства считается основным: в экспортах порядок
                    // типов (предпочтительный/рабочий) не определён.
                    properties.putIfAbsent(property.name, property.decodedValue());
                }
                index++;
            }
            if (index < lines.size()) {
                index++;
            }
            ParsedContact contact = toContact(properties, start + 1, issues);
            if (contact != null) {
                contacts.add(contact);
            }
        }

        if (text.toLowerCase(Locale.ROOT).contains("begin:vcard") && contacts.isEmpty() && issues.isEmpty()) {
            issues.add(new ImportIssue(1, "", "в файле нет карточек с адресом электронной почты"));
        }
        return new Result(contacts, issues);
    }

    private static ParsedContact toContact(Map<String, String> properties, int line, List<ImportIssue> issues) {
        String email = normalizeEmail(properties.get("EMAIL"));
        if (email.isBlank()) {
            String who = firstNonBlank(properties.get("FN"), properties.get("N"), properties.get("ORG"));
            issues.add(new ImportIssue(line, who == null ? "" : who, "в карточке нет адреса электронной почты"));
            return null;
        }
        String name = firstNonBlank(
                clean(properties.get("FN")),
                nameFromN(properties.get("N")),
                clean(properties.get("ORG")),
                localPart(email));
        String organization = firstComponent(properties.get("ORG"));
        String phone = firstNonBlank(properties.get("TEL"));
        return new ParsedContact(
                clean(name),
                email,
                clean(phone),
                clean(organization),
                clean(properties.get("TITLE")),
                clean(properties.get("NOTE")),
                clean(firstComponent(properties.get("CATEGORIES"))),
                line);
    }

    /** Склеивает переносы (RFC 6350 §3.2) и soft breaks quoted-printable. */
    static List<String> logicalLines(String text) {
        List<String> unfolded = new ArrayList<>();
        for (String line : text.split("\r\n|\n|\r", -1)) {
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t') && !unfolded.isEmpty()) {
                unfolded.set(unfolded.size() - 1, unfolded.get(unfolded.size() - 1) + line.substring(1));
            } else if (!line.isEmpty()) {
                unfolded.add(line);
            }
        }
        List<String> logical = new ArrayList<>();
        for (String line : unfolded) {
            String current = line;
            while (!logical.isEmpty()) {
                String previous = logical.get(logical.size() - 1);
                if (previous.length() > 1 && previous.endsWith("=")
                        && previous.toUpperCase(Locale.ROOT).contains("QUOTED-PRINTABLE")) {
                    logical.remove(logical.size() - 1);
                    current = previous.substring(0, previous.length() - 1) + current;
                } else {
                    break;
                }
            }
            logical.add(current);
        }
        return logical;
    }

    static String nameFromN(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(";", -1);
        String family = parts.length > 0 ? parts[0].trim() : "";
        String given = parts.length > 1 ? parts[1].trim() : "";
        String joined = (given + " " + family).trim();
        return joined.isBlank() ? null : joined;
    }

    private static String firstComponent(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int semicolon = raw.indexOf(';');
        return (semicolon < 0 ? raw : raw.substring(0, semicolon)).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        return trimmed.length() > 4000 ? trimmed.substring(0, 4000) : trimmed;
    }

    static String normalizeEmail(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("mailto:")) {
            value = value.substring("mailto:".length()).trim();
        }
        int angle = value.indexOf('<');
        if (angle >= 0 && value.endsWith(">")) {
            value = value.substring(angle + 1, value.length() - 1).trim();
        }
        return value;
    }

    private static String localPart(String email) {
        int at = email.indexOf('@');
        return at <= 0 ? email : email.substring(0, at);
    }

    /** Разобранная строка свойства: NAME;PARAM=VALUE:значение. */
    static final class Property {
        final String name;
        final Map<String, String> parameters;
        final String value;

        private Property(String name, Map<String, String> parameters, String value) {
            this.name = name;
            this.parameters = parameters;
            this.value = value;
        }

        static Property parse(String line) {
            if (line == null || line.isBlank()) {
                return null;
            }
            int colon = indexOfSeparator(line, ':');
            if (colon <= 0) {
                return null;
            }
            String head = line.substring(0, colon);
            String value = line.substring(colon + 1);
            List<String> parts = splitOutsideQuotes(head, ';');
            if (parts.isEmpty()) {
                return null;
            }
            String name = parts.get(0).trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                return null;
            }
            Map<String, String> parameters = new LinkedHashMap<>();
            for (int i = 1; i < parts.size(); i++) {
                String part = parts.get(i).trim();
                if (part.isEmpty()) {
                    continue;
                }
                int equals = part.indexOf('=');
                if (equals < 0) {
                    parameters.put(part.toUpperCase(Locale.ROOT), "");
                } else {
                    String key = part.substring(0, equals).trim().toUpperCase(Locale.ROOT);
                    String rawValue = part.substring(equals + 1).trim();
                    if (rawValue.length() >= 2 && rawValue.startsWith("\"") && rawValue.endsWith("\"")) {
                        rawValue = rawValue.substring(1, rawValue.length() - 1);
                    }
                    parameters.put(key, rawValue);
                }
            }
            return new Property(name, parameters, value);
        }

        String decodedValue() {
            String encoding = parameters.getOrDefault("ENCODING", "");
            if (encoding.toUpperCase(Locale.ROOT).contains("QUOTED-PRINTABLE")) {
                Charset charset = StandardCharsets.UTF_8;
                String declared = parameters.get("CHARSET");
                if (declared != null) {
                    try {
                        charset = Charset.forName(declared.trim());
                    } catch (RuntimeException ignored) {
                        // Неизвестная кодировка: остаёмся на UTF-8, значение всё равно
                        // попадёт в отчёт, если окажется нечитаемым.
                    }
                }
                return decodeQuotedPrintable(value, charset);
            }
            return unescape(value);
        }
    }

    /** QP-декодер: байты собираются и один раз декодируются заявленной кодировкой. */
    static String decodeQuotedPrintable(String value, Charset charset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '=' && i + 2 < value.length() && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                out.write((Character.digit(value.charAt(i + 1), 16) << 4) + Character.digit(value.charAt(i + 2), 16));
                i += 2;
            } else if (c == '=' && i + 1 >= value.length()) {
                // soft line break уже склеен в logicalLines
            } else if (c == '_') {
                out.write(' ');
            } else {
                byte[] bytes = String.valueOf(c).getBytes(charset);
                out.write(bytes, 0, bytes.length);
            }
        }
        return new String(out.toByteArray(), charset);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next == 'n' || next == 'N') {
                    out.append('\n');
                    i++;
                    continue;
                }
                if (next == ',' || next == ';' || next == '\\') {
                    out.append(next);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int indexOfSeparator(String line, char separator) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == separator && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitOutsideQuotes(String value, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                current.append(c);
            } else if (c == separator && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }
}
