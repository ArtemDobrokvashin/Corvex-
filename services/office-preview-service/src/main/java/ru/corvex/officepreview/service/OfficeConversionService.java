package ru.corvex.officepreview.service;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.officepreview.config.OfficePreviewProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Оркестрация: лимиты → подсчёт sha256 → кэш → слот → soffice → проверка PDF → кэш.
 * Один и тот же документ одновременно конвертируется один раз, остальные ждут результат.
 */
@Service
public class OfficeConversionService {

    private static final Logger log = LoggerFactory.getLogger(OfficeConversionService.class);
    private static final int BUFFER = 64 * 1024;

    private final OfficePreviewProperties properties;
    private final ConversionCache cache;
    private final SofficeRunner runner;
    private final MeterRegistry meters;
    private static final int LOCK_STRIPES = 64;
    private final Semaphore slots;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public OfficeConversionService(OfficePreviewProperties properties, ConversionCache cache,
                                   SofficeRunner runner, MeterRegistry meters) {
        this.properties = properties;
        this.cache = cache;
        this.runner = runner;
        this.meters = meters;
        this.slots = new Semaphore(Math.max(1, properties.concurrency()));
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    @PostConstruct
    void init() {
        if (!properties.enabled()) {
            log.info("Office-предпросмотр выключен (CORVEX_OFFICE_PREVIEW_ENABLED=false)");
            return;
        }
        String version = runner.version();
        if (version == null) {
            log.warn("LibreOffice не найден; предпросмотр будет отвечать 503");
            return;
        }
        log.info("Office-предпросмотр включён: {}", version);
        cache.resetIfVersionChanged(version);
    }

    public record Conversion(Path pdf, boolean cacheHit) {
    }

    public Conversion convert(String fileName, long declaredSize, InputStream input) throws IOException {
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Предпросмотр Office выключен");
        }
        String extension = OfficeFileNames.extension(fileName);
        long limit = properties.maxBytes().toBytes();
        if (declaredSize > limit) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Файл больше " + limit + " байт");
        }

        long started = System.nanoTime();
        try {
            return doConvert(extension, limit, input);
        } catch (RuntimeException e) {
            meters.counter("corvex.office.preview.failures").increment();
            throw e;
        } finally {
            meters.timer("corvex.office.preview.convert")
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private Conversion doConvert(String extension, long limit, InputStream input) throws IOException {
        Path workDir = newWorkDir();
        try {
            Path source = workDir.resolve("source." + extension);
            String sha256 = copyAndHash(input, source, limit);

            Optional<Path> cached = cache.find(sha256);
            if (cached.isPresent()) {
                meters.counter("corvex.office.preview.cache", "result", "hit").increment();
                return new Conversion(cached.get(), true);
            }
            meters.counter("corvex.office.preview.cache", "result", "miss").increment();

            if (!slots.tryAcquire()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Все слоты конвертации заняты, повторите позже");
            }
            ReentrantLock lock = locks[(sha256.hashCode() & 0x7fffffff) % LOCK_STRIPES];
            try {
                lock.lock();
                Optional<Path> rechecked = cache.find(sha256);
                if (rechecked.isPresent()) {
                    return new Conversion(rechecked.get(), true);
                }
                Path pdf = runner.convert(source, workDir);
                validatePdf(pdf);
                Path stored = cache.store(sha256, pdf);
                log.info("Office-документ сконвертирован: sha256={}, pdf={} байт", sha256, Files.size(stored));
                return new Conversion(stored, false);
            } finally {
                lock.unlock();
                slots.release();
            }
        } finally {
            deleteRecursively(workDir);
        }
    }

    private Path newWorkDir() {
        try {
            Files.createDirectories(properties.workDir());
            return Files.createTempDirectory(properties.workDir(), "office-preview-");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Нет места для временных файлов конвертера");
        }
    }

    private String copyAndHash(InputStream input, Path target, long limit) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
        long total = 0;
        byte[] buffer = new byte[BUFFER];
        try (OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "Файл больше " + limit + " байт");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (total == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Пустой файл");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void validatePdf(Path pdf) throws IOException {
        long size = Files.size(pdf);
        if (size <= 0 || size > properties.maxPdfBytes().toBytes()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PDF не создан или превысил лимит " + properties.maxPdfBytes().toBytes() + " байт");
        }
        byte[] bytes = Files.readAllBytes(pdf);
        if (bytes.length < 5 || !"%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Результат не является PDF");
        }
        int pages = countPages(bytes);
        if (pages > properties.maxPages()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PDF содержит " + pages + " страниц, лимит " + properties.maxPages());
        }
    }

    /** Приблизительный подсчёт: сжатые object streams могут дать 0, это не ошибка. */
    static int countPages(byte[] pdf) {
        byte[] needle = "/Type /Page".getBytes(StandardCharsets.ISO_8859_1);
        int count = 0;
        for (int i = 0; i <= pdf.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (pdf[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (!match) {
                continue;
            }
            int next = i + needle.length;
            if (next >= pdf.length || pdf[next] != 's') {
                count++;
            }
            i += needle.length - 1;
        }
        return count;
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("Не удалось удалить временный файл {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Не удалось очистить временный каталог {}: {}", root, e.getMessage());
        }
    }
}
