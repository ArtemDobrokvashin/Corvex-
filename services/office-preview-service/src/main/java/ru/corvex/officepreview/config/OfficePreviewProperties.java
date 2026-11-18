package ru.corvex.officepreview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Настройки конвертера. Значения по умолчанию заданы в application.yml,
 * снаружи переопределяются переменными CORVEX_OFFICE_*.
 */
@ConfigurationProperties(prefix = "corvex.office")
public record OfficePreviewProperties(
        boolean enabled,
        String sofficePath,
        Duration timeout,
        Duration versionTimeout,
        DataSize maxBytes,
        DataSize maxPdfBytes,
        int maxPages,
        int concurrency,
        Path cacheDir,
        Duration cacheTtl,
        DataSize cacheMaxBytes,
        Path workDir
) {
}
