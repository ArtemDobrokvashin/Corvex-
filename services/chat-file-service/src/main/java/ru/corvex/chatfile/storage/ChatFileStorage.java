package ru.corvex.chatfile.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import ru.corvex.chatfile.domain.StoredChatFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable filesystem-хранилище chat-file-service.
 *
 * <p>Байты лежат в content-addressable storage {@code objects/ab/cd/<sha256>.bin}; права,
 * комната, владелец и исходное имя — в атомарно публикуемых JSON-метаданных {@code meta/xx/<id>.json}.
 * Обе части находятся на одном persistent volume и одинаково попадают в backup.</p>
 */
@Component
public class ChatFileStorage {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int PREFIX_SIZE = 32;

    private final Path root;
    private final Path tmpDir;
    private final Path objectsDir;
    private final Path metaDir;
    private final ObjectMapper objectMapper;

    public ChatFileStorage(
            @Value("${corvex.files.root:${CORVEX_CHAT_FILE_ROOT:/data/chat-files}}") String root,
            ObjectMapper objectMapper) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.tmpDir = this.root.resolve("tmp");
        this.objectsDir = this.root.resolve("objects");
        this.metaDir = this.root.resolve("meta");
        this.objectMapper = objectMapper;
    }

    public record StagedUpload(Path path, long sizeBytes, String sha256, byte[] prefix) {
    }

    public static class FileTooLargeException extends IOException {
        private final long maxBytes;

        public FileTooLargeException(long maxBytes) {
            super("File exceeds configured limit of " + maxBytes + " bytes");
            this.maxBytes = maxBytes;
        }

        public long maxBytes() {
            return maxBytes;
        }
    }

    public StagedUpload stage(InputStream input, long maxBytes) throws IOException {
        ensureDirectories();
        Path temp = Files.createTempFile(tmpDir, "upload-", ".part");
        MessageDigest digest = sha256();
        byte[] prefix = new byte[PREFIX_SIZE];
        int prefixLength = 0;
        long size = 0;
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                size += read;
                if (size > maxBytes) {
                    throw new FileTooLargeException(maxBytes);
                }
                digest.update(buffer, 0, read);
                if (prefixLength < prefix.length) {
                    int copy = Math.min(read, prefix.length - prefixLength);
                    System.arraycopy(buffer, 0, prefix, prefixLength, copy);
                    prefixLength += copy;
                }
                ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, read);
                while (chunk.hasRemaining()) {
                    channel.write(chunk);
                }
            }
            channel.force(true);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw e;
        }
        byte[] actualPrefix = new byte[prefixLength];
        System.arraycopy(prefix, 0, actualPrefix, 0, prefixLength);
        return new StagedUpload(temp, size, HexFormat.of().formatHex(digest.digest()), actualPrefix);
    }

    public Path publishBlob(StagedUpload staged) throws IOException {
        Path target = blobPath(staged.sha256());
        Files.createDirectories(target.getParent());
        if (Files.isRegularFile(target)) {
            deleteQuietly(staged.path());
            return target;
        }
        try {
            moveAtomically(staged.path(), target, false);
        } catch (FileAlreadyExistsException e) {
            // Параллельная загрузка того же содержимого уже опубликовала blob.
            deleteQuietly(staged.path());
        }
        return target;
    }

    public void writeMetadata(StoredChatFile metadata) throws IOException {
        Path target = metadataPath(metadata.id());
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), metadata.id() + "-", ".tmp");
        try {
            byte[] json = objectMapper.writeValueAsBytes(metadata);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            moveAtomically(temp, target, true);
        } finally {
            deleteQuietly(temp);
        }
    }

    public Optional<StoredChatFile> loadMetadata(UUID id) throws IOException {
        Path path = metadataPath(id);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(path.toFile(), StoredChatFile.class));
    }

    public Optional<Resource> openContent(StoredChatFile metadata) {
        Path path = blobPath(metadata.sha256());
        return Files.isRegularFile(path)
                ? Optional.of(new FileSystemResource(path))
                : Optional.empty();
    }

    public Path blobPath(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256 value");
        }
        return objectsDir
                .resolve(sha256.substring(0, 2))
                .resolve(sha256.substring(2, 4))
                .resolve(sha256 + ".bin");
    }

    public Path metadataPath(UUID id) {
        String text = id.toString();
        return metaDir.resolve(text.substring(0, 2)).resolve(text + ".json");
    }

    public void deleteStaged(StagedUpload staged) {
        if (staged != null) {
            deleteQuietly(staged.path());
        }
    }

    public void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Временный файл можно безопасно убрать фоновой очисткой/при следующем обслуживании.
        }
    }

    private void ensureDirectories() throws IOException {
        Files.createDirectories(tmpDir);
        Files.createDirectories(objectsDir);
        Files.createDirectories(metaDir);
    }

    private void moveAtomically(Path source, Path target, boolean replaceExisting) throws IOException {
        try {
            if (replaceExisting) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException e) {
            if (replaceExisting) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
