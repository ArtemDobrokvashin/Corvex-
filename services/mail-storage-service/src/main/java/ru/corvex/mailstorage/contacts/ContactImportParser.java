package ru.corvex.mailstorage.contacts;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Входная точка импорта: определяет формат, кодировку и делегирует разбор.
 *
 * <p>Кодировка: BOM (UTF-8/UTF-16), затем строгий UTF-8, затем Windows-1251. Без этого
 * экспорт из старого Outlook в CP1251 превращался бы в мусор, который невозможно
 * разобрать обратно.
 */
public final class ContactImportParser {

    public enum Format { AUTO, VCARD, CSV }

    public record Result(List<ParsedContact> contacts, List<ImportIssue> issues, Format format) {
    }

    private ContactImportParser() {
    }

    public static Result parse(String fileName, byte[] bytes, Format requested) {
        Format format = requested == null || requested == Format.AUTO ? detect(fileName, bytes) : requested;
        String text = decode(bytes);
        if (format == Format.VCARD) {
            var result = ContactVCardParser.parse(text);
            return new Result(result.contacts(), result.issues(), Format.VCARD);
        }
        var result = ContactCsvParser.parse(text);
        return new Result(result.contacts(), result.issues(), Format.CSV);
    }

    static Format detect(String fileName, byte[] bytes) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".vcf") || name.endsWith(".vcard")) {
            return Format.VCARD;
        }
        if (name.endsWith(".csv") || name.endsWith(".tsv")) {
            return Format.CSV;
        }
        // .txt и файлы без расширения смотрим по содержимому: в них бывает и vCard,
        // и CSV, а угадывание по имени даёт мусор вместо контактов.
        String head = new String(bytes, 0, Math.min(bytes.length, 2048), StandardCharsets.ISO_8859_1)
                .toUpperCase(Locale.ROOT);
        return head.contains("BEGIN:VCARD") ? Format.VCARD : Format.CSV;
    }

    static String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        if (isValidUtf8(bytes)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return new String(bytes, Charset.forName("windows-1251"));
    }

    private static boolean isValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
