package ru.corvex.officepreview.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.corvex.officepreview.config.OfficePreviewProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Дисковый кэш PDF: <sha256>.pdf в двухуровневых каталогах.
 * Ключ — содержимое исходника; версия LibreOffice хранится в маркере и очищает кэш при смене.
 * AV-статус здесь не хранится: его проверяет вызывающий сервис на каждом запросе.
 */
@Component
public class ConversionCache {

    private static final Logger log = LoggerFactory.getLogger(ConversionCache.class);
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final String VERSION_MARKER = "libreoffice-version";

    private final Path root;
    private final Duration ttl;
    private final long maxBytes;

    public ConversionCache(OfficePreviewProperties properties) {
        this.root = properties.cacheDir();
        this.ttl = properties.cacheTtl();
        this.maxBytes = properties.cacheMaxBytes().toBytes();
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            // Выключенный предпросмотр не должен ронять сервис из-за прав на каталог.
            log.warn("Каталог кэша {} недоступен: {}", root, e.getMessage());
        }
    }

    public Optional<Path> find(String sha256) {
        if (sha256 == null || !SHA.matcher(sha256).matches()) {
            return Optional.empty();
        }
        Path pdf = pathFor(sha256);
        if (!Files.isRegularFile(pdf)) {
            return Optional.empty();
        }
        try {
            // LRU-подсказка для уборки: давно не открытые файлы удаляются первыми.
            Files.setLastModifiedTime(pdf, FileTime.from(Instant.now()));
        } catch (IOException e) {
            log.debug("Не удалось обновить mtime кэша {}: {}", pdf, e.getMessage());
        }
        return Optional.of(pdf);
    }

    public Path store(String sha256, Path pdf) throws IOException {
        Path target = pathFor(sha256);
        Files.createDirectories(target.getParent());
        if (Files.isRegularFile(target)) {
            return target;
        }
        Path temporary = Files.createTempFile(target.getParent(), ".pdf-", ".tmp");
        try {
            Files.copy(pdf, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }

    /**
     * Смена версии LibreOffice меняет вёрстку PDF, поэтому старый кэш бесполезен.
     * null/blank означает «версию узнать не удалось» — кэш в этом случае не трогаем.
     */
    public void resetIfVersionChanged(String version) {
        if (version == null || version.isBlank()) {
            return;
        }
        Path marker = root.resolve(VERSION_MARKER);
        try {
            String previous = Files.isRegularFile(marker) ? Files.readString(marker).trim() : null;
            if (version.equals(previous)) {
                return;
            }
            log.info("Версия LibreOffice изменилась ({} -> {}), очищаю кэш предпросмотра", previous, version);
            deletePdfs();
            Files.writeString(marker, version);
        } catch (IOException e) {
            log.warn("Не удалось проверить версию LibreOffice: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${corvex.office.cleanup-interval-ms:3600000}",
            initialDelayString = "${corvex.office.cleanup-initial-delay-ms:60000}")
    public void cleanup() {
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant deadline = Instant.now().minus(ttl);
        List<Path> pdfs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".pdf"))
                    .forEach(pdfs::add);
        } catch (IOException e) {
            log.warn("Не удалось прочитать кэш предпросмотра: {}", e.getMessage());
            return;
        }
        List<Path> alive = new ArrayList<>();
        for (Path pdf : pdfs) {
            try {
                if (Files.getLastModifiedTime(pdf).toInstant().isBefore(deadline)) {
                    Files.deleteIfExists(pdf);
                } else {
                    alive.add(pdf);
                }
            } catch (IOException e) {
                log.debug("Не удалось удалить просроченный кэш {}: {}", pdf, e.getMessage());
                alive.add(pdf);
            }
        }
        long total = 0;
        List<Path> withSize = new ArrayList<>();
        for (Path pdf : alive) {
            try {
                total += Files.size(pdf);
                withSize.add(pdf);
            } catch (IOException e) {
                log.debug("Не удалось прочитать размер {}: {}", pdf, e.getMessage());
            }
        }
        if (total <= maxBytes) {
            return;
        }
        withSize.sort(Comparator.comparingLong(ConversionCache::lastModifiedMillis));
        for (Path pdf : withSize) {
            if (total <= maxBytes) {
                break;
            }
            try {
                long size = Files.size(pdf);
                if (Files.deleteIfExists(pdf)) {
                    total -= size;
                }
            } catch (IOException e) {
                log.debug("Не удалось вытеснить кэш {}: {}", pdf, e.getMessage());
            }
        }
    }

    private void deletePdfs() throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> pdfs = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".pdf"))
                    .toList();
            for (Path pdf : pdfs) {
                Files.deleteIfExists(pdf);
            }
        }
    }

    private Path pathFor(String sha256) {
        return root.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256 + ".pdf");
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
