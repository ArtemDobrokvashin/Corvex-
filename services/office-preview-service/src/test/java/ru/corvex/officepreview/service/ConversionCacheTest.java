package ru.corvex.officepreview.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import ru.corvex.officepreview.config.OfficePreviewProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversionCacheTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @TempDir
    Path temp;

    @Test
    void storesAndFindsPdfBySha() throws Exception {
        ConversionCache cache = cache(Duration.ofDays(30), DataSize.ofMegabytes(1));
        Path source = pdf("first", 10);

        Path stored = cache.store(SHA_A, source);

        assertTrue(Files.isRegularFile(stored));
        assertEquals(stored, cache.find(SHA_A).orElseThrow());
        assertTrue(cache.find("not-a-sha").isEmpty());
    }

    @Test
    void deletesExpiredFiles() throws Exception {
        ConversionCache cache = cache(Duration.ofDays(1), DataSize.ofMegabytes(1));
        Path stored = cache.store(SHA_A, pdf("old", 10));
        Files.setLastModifiedTime(stored, FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        cache.cleanup();

        assertFalse(Files.exists(stored));
    }

    @Test
    void evictsOldestFilesAboveQuota() throws Exception {
        ConversionCache cache = cache(Duration.ofDays(30), DataSize.ofBytes(10));
        Path first = cache.store(SHA_A, pdf("11111111", 8));
        Path second = cache.store(SHA_B, pdf("22222222", 8));
        Files.setLastModifiedTime(first, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        Files.setLastModifiedTime(second, FileTime.from(Instant.now()));

        cache.cleanup();

        assertFalse(Files.exists(first));
        assertTrue(Files.exists(second));
    }

    @Test
    void clearsCacheWhenLibreOfficeVersionChanges() throws Exception {
        ConversionCache cache = cache(Duration.ofDays(30), DataSize.ofMegabytes(1));
        Path stored = cache.store(SHA_A, pdf("data", 10));

        cache.resetIfVersionChanged("LibreOffice 7.6.4.1");
        assertFalse(Files.exists(stored));

        Path again = cache.store(SHA_A, pdf("data", 10));
        cache.resetIfVersionChanged("LibreOffice 7.6.4.1");
        assertTrue(Files.exists(again));
    }

    private ConversionCache cache(Duration ttl, DataSize maxBytes) throws Exception {
        var properties = new OfficePreviewProperties(
                true, "/usr/bin/soffice", Duration.ofSeconds(20), Duration.ofSeconds(5),
                DataSize.ofMegabytes(25), DataSize.ofMegabytes(50), 500, 2,
                temp.resolve("cache"), ttl, maxBytes, temp.resolve("work"));
        var cache = new ConversionCache(properties);
        cache.init();
        return cache;
    }

    private Path pdf(String content, int size) throws Exception {
        Path file = Files.createTempFile(temp, "pdf-", ".pdf");
        byte[] bytes = new byte[size];
        byte[] prefix = "%PDF-".getBytes();
        System.arraycopy(prefix, 0, bytes, 0, Math.min(prefix.length, bytes.length));
        byte[] contentBytes = content.getBytes();
        System.arraycopy(contentBytes, 0, bytes, Math.min(5, size),
                Math.min(contentBytes.length, Math.max(0, size - 5)));
        Files.write(file, bytes);
        return file;
    }
}
