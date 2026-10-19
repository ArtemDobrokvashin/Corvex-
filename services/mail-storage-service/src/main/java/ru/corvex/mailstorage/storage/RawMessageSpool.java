package ru.corvex.mailstorage.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Durable spool сырых входящих писем: SMTP получает право ответить 250 OK только после
 * того, как .eml целиком записан, fsync-нут и атомарно переименован в этот каталог.
 * Лимит размера проверяется потоково — большое письмо не попадает в heap целиком.
 */
@Component
public class RawMessageSpool {

    private static final Pattern RAW_REF = Pattern.compile("[0-9a-fA-F-]{36}");
    private static final int BUFFER_BYTES = 64 * 1024;

    private final Path spoolRoot;
    private final long maxMessageBytes;

    public RawMessageSpool(@Value("${corvex.mail.spool:/data/mail/_spool}") Path spoolRoot,
                           @Value("${corvex.mail.max-message-bytes:26214400}") long maxMessageBytes) {
        this.spoolRoot = spoolRoot;
        this.maxMessageBytes = maxMessageBytes;
    }

    public record StoredRawMessage(String rawRef, String sha256, long sizeBytes) {
    }

    public static class MessageTooLargeException extends IOException {
        public MessageTooLargeException(long limit) {
            super("Message exceeds maximum size of " + limit + " bytes");
        }
    }

    public StoredRawMessage store(InputStream in) throws IOException {
        Files.createDirectories(spoolRoot);
        Path tmpDir = spoolRoot.resolve("tmp");
        Files.createDirectories(tmpDir);

        String rawRef = UUID.randomUUID().toString();
        Path tmp = tmpDir.resolve(rawRef + ".part");
        Path target = path(rawRef);
        MessageDigest sha = sha256();
        long size = 0;
        try {
            try (var out = new FileOutputStream(tmp.toFile());
                 var digestOut = new DigestOutputStream(out, sha)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    size += read;
                    if (size > maxMessageBytes) {
                        throw new MessageTooLargeException(maxMessageBytes);
                    }
                    digestOut.write(buffer, 0, read);
                }
                digestOut.flush();
                out.getChannel().force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target);
            }
            syncDirectory(spoolRoot);
            return new StoredRawMessage(rawRef, HexFormat.of().formatHex(sha.digest()), size);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    public InputStream open(String rawRef) throws IOException {
        return Files.newInputStream(path(rawRef));
    }

    public long size(String rawRef) throws IOException {
        return Files.size(path(rawRef));
    }

    public boolean exists(String rawRef) {
        return Files.exists(path(rawRef));
    }

    public void delete(String rawRef) throws IOException {
        Files.deleteIfExists(path(rawRef));
    }

    Path path(String rawRef) {
        if (rawRef == null || !RAW_REF.matcher(rawRef).matches()) {
            throw new IllegalArgumentException("Некорректный rawRef");
        }
        return spoolRoot.resolve(rawRef + ".eml");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void syncDirectory(Path dir) {
        // На Linux fsync каталога фиксирует сам rename; на ФС без поддержки просто идём дальше:
        // файл уже fsync-нут до rename, каталог — дополнительное усиление, а не единственный барьер.
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // best effort
        }
    }
}
