package ru.corvex.officepreview.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.officepreview.config.OfficePreviewProperties;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC})
class OfficeConversionServiceTest {

    @TempDir
    Path temp;

    @Test
    void convertsOnceThenServesTheCachedPdf() throws Exception {
        Script script = script("ok", "ok");
        var service = service(script, Duration.ofSeconds(5), DataSize.ofMegabytes(25));

        var first = service.convert("report.docx", 12, input("hello office"));
        var second = service.convert("report.docx", 12, input("hello office"));

        assertFalse(first.cacheHit());
        assertTrue(second.cacheHit());
        assertTrue(Files.readString(first.pdf()).startsWith("%PDF-"));
        assertEquals(List.of("run"), Files.readAllLines(script.dir().resolve("runs")));
    }

    @Test
    void rejectsOversizedInputBeforeConversion() throws Exception {
        Script script = script("ok", "ok");
        var service = service(script, Duration.ofSeconds(5), DataSize.ofBytes(10));

        var error = assertThrows(ResponseStatusException.class,
                () -> service.convert("report.docx", 11, input("12345678901")));
        assertEquals(413, error.getStatusCode().value());
    }

    @Test
    void rejectsEmptyInput() throws Exception {
        Script script = script("ok", "ok");
        var service = service(script, Duration.ofSeconds(5), DataSize.ofMegabytes(25));

        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.convert("report.docx", 0, input(""))).getStatusCode().value());
    }

    @Test
    void rejectsUnsupportedExtensions() throws Exception {
        Script script = script("ok", "ok");
        var service = service(script, Duration.ofSeconds(5), DataSize.ofMegabytes(25));

        assertEquals(415, assertThrows(ResponseStatusException.class,
                () -> service.convert("malware.exe", 1, input("x"))).getStatusCode().value());
    }

    @Test
    void reportsConvertedFailureAsUnprocessable() throws Exception {
        Script script = script("fail", "fail");
        var service = service(script, Duration.ofSeconds(5), DataSize.ofMegabytes(25));

        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> service.convert("report.docx", 1, input("x"))).getStatusCode().value());
    }

    @Test
    void reportsMissingOutputAsUnprocessable() throws Exception {
        Script script = script("empty", "empty");
        var service = service(script, Duration.ofSeconds(5), DataSize.ofMegabytes(25));

        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> service.convert("report.docx", 1, input("x"))).getStatusCode().value());
    }

    @Test
    void killsConversionOnTimeout() throws Exception {
        Script script = script("slow", "slow");
        var service = service(script, Duration.ofMillis(200), DataSize.ofMegabytes(25));

        var error = assertThrows(ResponseStatusException.class,
                () -> service.convert("report.docx", 1, input("x")));
        assertEquals(504, error.getStatusCode().value());
    }

    @Test
    void disabledConverterReturnsNotImplemented() throws Exception {
        Script script = script("ok", "ok");
        var props = properties(script, Duration.ofSeconds(5), DataSize.ofMegabytes(25), false);
        var cache = new ConversionCache(props);
        cache.init();
        var service = new OfficeConversionService(props, cache, new SofficeRunner(props),
                new SimpleMeterRegistry());

        assertEquals(501, assertThrows(ResponseStatusException.class,
                () -> service.convert("report.docx", 1, input("x"))).getStatusCode().value());
    }

    private OfficeConversionService service(Script script, Duration timeout, DataSize maxBytes) {
        var props = properties(script, timeout, maxBytes, true);
        var cache = new ConversionCache(props);
        cache.init();
        return new OfficeConversionService(props, cache, new SofficeRunner(props), new SimpleMeterRegistry());
    }

    private OfficePreviewProperties properties(Script script, Duration timeout, DataSize maxBytes, boolean enabled) {
        return new OfficePreviewProperties(
                enabled,
                script.path().toString(),
                timeout,
                Duration.ofSeconds(2),
                maxBytes,
                DataSize.ofMegabytes(50),
                500,
                2,
                temp.resolve("cache"),
                Duration.ofDays(30),
                DataSize.ofGigabytes(1),
                temp.resolve("work"));
    }

    /**
     * Подставной soffice: поведение задаётся режимом, а не флагами, потому что
     * «нет результата» и «ошибка конвертации» — разные ветки кода, и раньше две проверки
     * молча получали успешный запуск (name == "fail"/"empty", а флаг fail был false).
     */
    private Script script(String name, String mode) throws Exception {
        Path dir = Files.createDirectory(temp.resolve("script-" + name));
        Path path = dir.resolve("soffice");
        String behaviour = switch (mode) {
            case "slow" -> "sleep 5\n";
            case "fail" -> "exit 1\n";
            case "empty" -> "exit 0\n";
            default -> """
                    cp "$(dirname "$0")/fake.pdf" "$outdir/result.pdf"
                    """;
        };
        String body = """
                #!/bin/sh
                if [ "${1:-}" = "--version" ]; then
                  echo "LibreOffice 7.6.4.1 fake"
                  exit 0
                fi
                outdir=""
                prev=""
                for arg in "$@"; do
                  if [ "$prev" = "--outdir" ]; then outdir="$arg"; fi
                  prev="$arg"
                done
                echo run >> "$(dirname "$0")/runs"
                """ + behaviour;
        Files.writeString(path, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(dir.resolve("fake.pdf"), "%PDF-1.4\n1 0 obj\n/Type /Page\n%%EOF\n",
                StandardCharsets.ISO_8859_1);
        return new Script(dir, path);
    }

    private ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Script(Path dir, Path path) {
    }
}
