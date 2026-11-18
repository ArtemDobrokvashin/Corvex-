package ru.corvex.officepreview.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Имя файла приходит в base64url: заголовки HTTP не переносят не-ASCII.
 * Наружу (в процесс) уходит только basename с безопасным расширением.
 */
public final class OfficeFileNames {

    public static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp");

    private OfficeFileNames() {
    }

    public static String decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Office-File-Name обязателен");
        }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Office-File-Name не base64url");
        }
        return sanitize(decoded);
    }

    static String sanitize(String name) {
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Пустое имя файла");
        }
        String normalized = name.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        normalized = normalized.replaceAll("\\p{Cntrl}", "_").trim();
        if (normalized.isEmpty() || normalized.length() > 200 || normalized.equals(".") || normalized.equals("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректное имя файла");
        }
        return normalized;
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "У файла нет расширения");
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Формат ." + extension + " не поддерживается");
        }
        return extension;
    }

    /** Имя PDF для Content-Disposition: тот же basename, без управляющих символов. */
    public static String pdfName(String sourceName) {
        String safe = sanitize(sourceName);
        return safe.toLowerCase(Locale.ROOT).endsWith(".pdf") ? safe : safe + ".pdf";
    }
}
