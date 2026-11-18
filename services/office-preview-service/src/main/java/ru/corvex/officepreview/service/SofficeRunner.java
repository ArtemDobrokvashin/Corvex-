package ru.corvex.officepreview.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.officepreview.config.OfficePreviewProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Запуск LibreOffice без shell: фиксированный список аргументов, отдельный профиль,
 * жёсткий таймаут и убийство дерева процессов.
 */
@Component
public class SofficeRunner {

    private static final Logger log = LoggerFactory.getLogger(SofficeRunner.class);

    private final OfficePreviewProperties properties;

    public SofficeRunner(OfficePreviewProperties properties) {
        this.properties = properties;
    }

    /** Первая строка `soffice --version` или null, если бинарь недоступен. */
    public String version() {
        Process process = null;
        try {
            process = new ProcessBuilder(properties.sofficePath(), "--version")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(properties.versionTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || output.isBlank()) {
                return null;
            }
            return output.lines().findFirst().orElse(null);
        } catch (IOException e) {
            log.warn("LibreOffice недоступен по пути {}: {}", properties.sofficePath(), e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public Path convert(Path input, Path workDir) throws IOException {
        Path profile = Files.createDirectory(workDir.resolve("lo-profile"));
        Path outputDir = Files.createDirectory(workDir.resolve("out"));
        List<String> command = List.of(
                properties.sofficePath(),
                "--headless",
                "--norestore",
                "--nolockcheck",
                "--nodefault",
                "--nofirststartwizard",
                "--nologo",
                "-env:UserInstallation=" + profile.toUri(),
                "--convert-to", "pdf",
                "--outdir", outputDir.toString(),
                input.toString());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        Thread drainer = new Thread(() -> drain(process.getInputStream()), "soffice-output");
        drainer.setDaemon(true);
        drainer.start();

        boolean finished;
        try {
            finished = process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Конвертация прервана");
        }
        if (!finished) {
            killTree(process);
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "Конвертация не уложилась в " + properties.timeout().toSeconds() + " с");
        }
        if (process.exitValue() != 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "LibreOffice завершился с кодом " + process.exitValue());
        }

        try (Stream<Path> files = Files.list(outputDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "LibreOffice не создал PDF"));
        }
    }

    /** soffice запускает soffice.bin: убивать нужно всё дерево, иначе процесс останется жить. */
    private void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private void drain(InputStream stream) {
        try (InputStream in = stream; OutputStream sink = OutputStream.nullOutputStream()) {
            in.transferTo(sink);
        } catch (IOException e) {
            log.debug("Поток вывода LibreOffice закрыт: {}", e.getMessage());
        }
    }
}
