package ru.corvex.mailimap.server;

import org.apache.james.filesystem.api.FileSystem;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Минимальная FileSystem для James AbstractConfigurableAsyncServer: Джеймсу она нужна почти
 * только чтобы открыть keystore/сертификаты из конфигурации TLS. Базовой директорией выступает
 * корень ФС контейнера, чтобы абсолютные пути вида /certs/imap.p12 проходили проверку Джеймса.
 */
final class LocalFileSystem implements FileSystem {

    static final LocalFileSystem INSTANCE = new LocalFileSystem();

    private LocalFileSystem() {
    }

    @Override
    public InputStream getResource(String url) throws IOException {
        if (url.startsWith(CLASSPATH_PROTOCOL)) {
            String resource = url.substring(CLASSPATH_PROTOCOL.length());
            InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
            if (in == null) {
                throw new FileNotFoundException("Ресурс не найден в classpath: " + resource);
            }
            return in;
        }
        return new FileInputStream(getFile(url));
    }

    @Override
    public File getFile(String fileURL) throws FileNotFoundException {
        File file = resolve(fileURL);
        if (!file.isFile() || !file.canRead()) {
            throw new FileNotFoundException("Файл не найден или недоступен для чтения: " + fileURL);
        }
        return file;
    }

    @Override
    public File getBasedir() {
        return Path.of("/").toFile();
    }

    private static File resolve(String fileURL) {
        String path = fileURL;
        if (path.startsWith(FILE_PROTOCOL_AND_CONF) || path.startsWith(FILE_PROTOCOL_AND_VAR)) {
            // file://conf/... и file://var/... в Джеймсе — относительно basedir; у нас basedir = /
            return Path.of("/" + path.substring(FILE_PROTOCOL.length())).toFile();
        }
        if (path.startsWith(FILE_PROTOCOL_ABSOLUTE)) {
            return Path.of("/" + path.substring(FILE_PROTOCOL_ABSOLUTE.length())).toFile();
        }
        if (path.startsWith(FILE_PROTOCOL)) {
            return Path.of(path.substring(FILE_PROTOCOL.length())).toFile();
        }
        return new File(path);
    }
}
