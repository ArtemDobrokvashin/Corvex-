package ru.corvex.chatfile.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import ru.corvex.chatfile.storage.ChatFileStorage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable-хранилище метаданных диска.
 *
 * <p>Blob'ы переиспользуют content-addressable storage chat-file-service
 * ({@code objects/ab/cd/<sha256>.bin}), а метаданные узлов и ссылок лежат рядом:
 * {@code drive/nodes/<owner>/<id>.json} и {@code drive/shares/<sha256(token)>.json}.
 * Каждая запись — временный файл + fsync + атомарная замена, поэтому оборванная
 * запись не оставляет полу-JSON. Один persistent volume покрывает и чат, и диск,
 * и существующие backup-скрипты не нужно менять.</p>
 */
@Component
public class DriveStorage {

    private static final Logger log = LoggerFactory.getLogger(DriveStorage.class);

    private final ChatFileStorage blobs;
    private final ObjectMapper objectMapper;
    private final Path tmpDir;
    private final Path nodesDir;
    private final Path sharesDir;

    public DriveStorage(
            ChatFileStorage blobs,
            @Value("${corvex.files.root:${CORVEX_CHAT_FILE_ROOT:/data/chat-files}}") String root,
            ObjectMapper objectMapper) {
        this.blobs = blobs;
        this.objectMapper = objectMapper;
        Path driveRoot = Path.of(root).toAbsolutePath().normalize().resolve("drive");
        this.tmpDir = driveRoot.resolve("tmp");
        this.nodesDir = driveRoot.resolve("nodes");
        this.sharesDir = driveRoot.resolve("shares");
    }

    public void saveNode(DriveNode node) throws IOException {
        Path target = nodePath(node.ownerId(), node.id());
        Files.createDirectories(target.getParent());
        writeAtomically(target, objectMapper.writeValueAsBytes(node));
    }

    public Optional<DriveNode> findNode(UUID ownerId, UUID id) throws IOException {
        Path path = nodePath(ownerId, id);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(path.toFile(), DriveNode.class));
    }

    /** Все узлы владельца, включая корзину: квоту и восстановление считаем по одному каталогу. */
    public List<DriveNode> listNodes(UUID ownerId) throws IOException {
        Path ownerDir = nodesDir.resolve(ownerId.toString());
        if (!Files.isDirectory(ownerDir)) {
            return List.of();
        }
        List<DriveNode> nodes = new ArrayList<>();
        try (var stream = Files.list(ownerDir)) {
            for (Path path : stream.toList()) {
                if (!path.getFileName().toString().endsWith(".json")) {
                    continue;
                }
                try {
                    nodes.add(objectMapper.readValue(path.toFile(), DriveNode.class));
                } catch (IOException e) {
                    // Битый файл не должен прятать остальные: он виден в логе и остаётся
                    // на диске для ручного разбора, но список продолжает работать.
                    log.warn("Drive node metadata is unreadable: {} ({})", path, e.getMessage());
                }
            }
        }
        return nodes;
    }

    /** Все узлы всех владельцев: нужен только при окончательной очистке, чтобы понять,
     *  ссылается ли ещё кто-то на blob (дедупликация между пользователями). */
    public List<DriveNode> listAllNodes() throws IOException {
        if (!Files.isDirectory(nodesDir)) {
            return List.of();
        }
        List<DriveNode> nodes = new ArrayList<>();
        try (var stream = Files.list(nodesDir)) {
            for (Path ownerDir : stream.toList()) {
                if (Files.isDirectory(ownerDir)) {
                    try {
                        nodes.addAll(listNodes(UUID.fromString(ownerDir.getFileName().toString())));
                    } catch (IllegalArgumentException ignored) {
                        // Не наш каталог — просто пропускаем.
                    }
                }
            }
        }
        return nodes;
    }

    public void deleteNode(UUID ownerId, UUID id) throws IOException {
        Files.deleteIfExists(nodePath(ownerId, id));
    }

    public void saveShare(DriveShare share) throws IOException {
        Path target = sharePath(share.tokenHash());
        Files.createDirectories(target.getParent());
        writeAtomically(target, objectMapper.writeValueAsBytes(share));
    }

    public Optional<DriveShare> findShare(String tokenHash) throws IOException {
        Path path = sharePath(tokenHash);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(path.toFile(), DriveShare.class));
    }

    /** Список ссылок владельца. Ссылок немного, поэтому честный скан дешевле второго индекса. */
    public List<DriveShare> listShares(UUID ownerId) throws IOException {
        if (!Files.isDirectory(sharesDir)) {
            return List.of();
        }
        List<DriveShare> shares = new ArrayList<>();
        // Файлы ссылок лежат в подкаталогах по первым двум символам хеша,
        // чтобы каталог не разрастался до тысяч записей.
        try (var buckets = Files.list(sharesDir)) {
            for (Path bucket : buckets.toList()) {
                if (!Files.isDirectory(bucket)) {
                    continue;
                }
                try (var stream = Files.list(bucket)) {
                    for (Path path : stream.toList()) {
                        if (!path.getFileName().toString().endsWith(".json")) {
                            continue;
                        }
                        try {
                            DriveShare share = objectMapper.readValue(path.toFile(), DriveShare.class);
                            if (share.ownerId().equals(ownerId)) {
                                shares.add(share);
                            }
                        } catch (IOException e) {
                            log.warn("Drive share metadata is unreadable: {} ({})", path, e.getMessage());
                        }
                    }
                }
            }
        }
        return shares;
    }

    public void deleteShare(DriveShare share) throws IOException {
        Files.deleteIfExists(sharePath(share.tokenHash()));
    }

    public Optional<Resource> openContent(DriveNode node) {
        if (node.kind() != DriveNode.Kind.FILE || node.sha256() == null) {
            return Optional.empty();
        }
        return blobs.openBlob(node.sha256());
    }

    public ChatFileStorage blobs() {
        return blobs;
    }

    public Path nodePath(UUID ownerId, UUID id) {
        return nodesDir.resolve(ownerId.toString()).resolve(id + ".json");
    }

    private Path sharePath(String tokenHash) {
        if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid share token hash");
        }
        return sharesDir.resolve(tokenHash.substring(0, 2)).resolve(tokenHash + ".json");
    }

    private void writeAtomically(Path target, byte[] payload) throws IOException {
        Files.createDirectories(tmpDir);
        Path temp = Files.createTempFile(tmpDir, "drive-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(payload);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
