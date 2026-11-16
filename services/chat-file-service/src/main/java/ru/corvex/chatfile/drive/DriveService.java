package ru.corvex.chatfile.drive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.chatfile.av.ClamAvClient;
import ru.corvex.chatfile.storage.ChatFileStorage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Личный диск сотрудника: папки, файлы, корзина и ссылки «как в Google Drive».
 *
 * <p>Правила проверяются на каждую операцию, а не один раз при загрузке:</p>
 * <ul>
 *   <li>владелец — единственный, кто видит содержимое без явно выданной ссылки;</li>
 *   <li>ссылка выдаётся только файлу, только владельцем и только явным действием;
 *       отзыв действует немедленно, потому что проверяется при каждом скачивании;</li>
 *   <li>файл не публикуется без успешной антивирусной проверки (fail-closed);</li>
 *   <li>квота проверяется до публикации blob'а, чтобы не занимать диск впустую.</li>
 * </ul>
 *
 * <p>Сервис рассчитан на один экземпляр: изменения метаданных сериализуются внутри процесса.
 * При появлении второй реплики потребуется общий координатор блокировок — это записано в
 * docs/FILES_PLAN.md как известное ограничение, а не как незамеченный компромисс.</p>
 */
@Service
public class DriveService {

    private static final Logger log = LoggerFactory.getLogger(DriveService.class);
    private static final int MAX_NAME_LENGTH = 200;
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final DriveStorage storage;
    private final ClamAvClient clam;
    private final long quotaBytes;
    private final long maxFileBytes;
    private final boolean avRequired;
    private final Object mutationLock = new Object();

    public DriveService(
            DriveStorage storage,
            ClamAvClient clam,
            @Value("${corvex.drive.quota-bytes:${CORVEX_DRIVE_QUOTA_BYTES:5368709120}}") long quotaBytes,
            @Value("${corvex.drive.max-file-bytes:${CORVEX_DRIVE_MAX_FILE_BYTES:26214400}}") long maxFileBytes,
            @Value("${corvex.files.av-required:${CORVEX_CHAT_FILE_AV_REQUIRED:true}}") boolean avRequired) {
        this.storage = storage;
        this.clam = clam;
        this.quotaBytes = Math.max(1, quotaBytes);
        this.maxFileBytes = Math.max(1, maxFileBytes);
        this.avRequired = avRequired;
    }

    public record Usage(long usedBytes, long quotaBytes) {
    }

    public record Download(DriveNode node, Resource resource) {
    }

    public record ShareCreated(DriveShare share, String token) {
    }

    public enum View {FILES, TRASH}

    public List<DriveNode> list(UUID ownerId, UUID parentId, View view, String query) {
        List<DriveNode> nodes = readNodes(ownerId);
        Map<UUID, DriveNode> byId = byId(nodes);
        if (parentId != null) {
            DriveNode parent = byId.get(parentId);
            if (parent == null || parent.isTrashed()) {
                throw notFound("Папка не найдена");
            }
            if (!parent.isFolder()) {
                throw badRequest("Это файл, а не папка");
            }
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return nodes.stream()
                .filter(node -> view == View.FILES ? !node.isTrashed() : node.isTrashed())
                .filter(node -> view == View.FILES
                        ? sameParent(node.parentId(), parentId)
                        : inTrashRoot(node, byId))
                .filter(node -> needle.isEmpty() || node.name().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator
                        .comparing((DriveNode node) -> !node.isFolder())
                        .thenComparing(node -> node.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * Все папки владельца, кроме корзины. Нужны диалогу перемещения: он показывает
     * плоский список с полными путями, а не заставляет пользователя «заходить» в папки
     * кликами — иначе клик по имени читается как выбор и кнопка подтверждения врёт.
     */
    public List<DriveNode> folders(UUID ownerId) {
        return readNodes(ownerId).stream()
                .filter(node -> node.kind() == DriveNode.Kind.FOLDER)
                .filter(node -> !node.isTrashed())
                .toList();
    }

    public Usage usage(UUID ownerId) {
        long used = readNodes(ownerId).stream()
                .filter(node -> node.kind() == DriveNode.Kind.FILE)
                .mapToLong(DriveNode::sizeBytes)
                .sum();
        return new Usage(used, quotaBytes);
    }

    public DriveNode createFolder(UUID ownerId, UUID parentId, String rawName) {
        String name = normalizeName(rawName);
        synchronized (mutationLock) {
            List<DriveNode> nodes = readNodes(ownerId);
            requireFolder(nodes, parentId);
            requireFreeName(nodes, parentId, name, null);
            Instant now = Instant.now();
            DriveNode folder = new DriveNode(UUID.randomUUID(), ownerId, parentId,
                    DriveNode.Kind.FOLDER, name, 0, null, null, null, now, now, null);
            writeNode(folder);
            return folder;
        }
    }

    public DriveNode upload(UUID ownerId, UUID parentId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw badRequest("Файл не выбран или пуст");
        }
        String fileName = normalizeName(file.getOriginalFilename());
        String declaredType = normalizeContentType(file.getContentType());
        ChatFileStorage.StagedUpload staged = null;
        try (InputStream input = file.getInputStream()) {
            staged = storage.blobs().stage(input, maxFileBytes);
            if (staged.sizeBytes() == 0) {
                throw badRequest("Файл не выбран или пуст");
            }
            ClamAvClient.ScanResult scan = scan(staged);
            String avStatus = switch (scan.result()) {
                case CLEAN -> "CLEAN";
                case INFECTED -> throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "Файл отклонён антивирусом");
                case ERROR -> {
                    if (avRequired) {
                        log.warn("ClamAV scan failed for drive upload of {}: {}", ownerId, scan.signature());
                        throw new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Антивирус временно недоступен, файл не опубликован");
                    }
                    yield "ERROR";
                }
            };
            DriveNode node;
            synchronized (mutationLock) {
                List<DriveNode> nodes = readNodes(ownerId);
                requireFolder(nodes, parentId);
                requireFreeName(nodes, parentId, fileName, null);
                long used = nodes.stream()
                        .filter(existing -> existing.kind() == DriveNode.Kind.FILE)
                        .mapToLong(DriveNode::sizeBytes)
                        .sum();
                if (used + staged.sizeBytes() > quotaBytes) {
                    throw new ResponseStatusException(
                            HttpStatus.PAYLOAD_TOO_LARGE,
                            "Недостаточно места на диске: занято " + used + " из " + quotaBytes + " байт");
                }
                Instant now = Instant.now();
                node = new DriveNode(UUID.randomUUID(), ownerId, parentId, DriveNode.Kind.FILE,
                        fileName, staged.sizeBytes(), detectContentType(declaredType, fileName),
                        staged.sha256(), avStatus, now, now, null);
                storage.blobs().publishBlob(staged);
                staged = null;
                writeNode(node);
            }
            return node;
        } catch (ChatFileStorage.FileTooLargeException e) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Файл превышает допустимый размер " + e.maxBytes() + " байт");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw internal("Не удалось сохранить файл", e);
        } finally {
            storage.blobs().deleteStaged(staged);
        }
    }

    public DriveNode rename(UUID ownerId, UUID id, String rawName) {
        String name = normalizeName(rawName);
        synchronized (mutationLock) {
            List<DriveNode> nodes = readNodes(ownerId);
            DriveNode node = requireNode(nodes, id);
            requireNotTrashed(node);
            requireFreeName(nodes, node.parentId(), name, node.id());
            DriveNode renamed = node.renamed(name);
            writeNode(renamed);
            return renamed;
        }
    }

    public DriveNode move(UUID ownerId, UUID id, UUID targetParentId) {
        synchronized (mutationLock) {
            List<DriveNode> nodes = readNodes(ownerId);
            DriveNode node = requireNode(nodes, id);
            requireNotTrashed(node);
            if (id.equals(targetParentId)) {
                throw badRequest("Нельзя переместить папку внутрь себя");
            }
            requireFolder(nodes, targetParentId);
            if (node.isFolder() && isDescendant(nodes, targetParentId, node.id())) {
                throw badRequest("Нельзя переместить папку внутрь своей вложенной папки");
            }
            requireFreeName(nodes, targetParentId, node.name(), node.id());
            DriveNode moved = node.movedTo(targetParentId);
            writeNode(moved);
            return moved;
        }
    }

    /** Корзина рекурсивная: иначе файл из удалённой папки остался бы видимым в поиске. */
    public void trash(UUID ownerId, UUID id) {
        synchronized (mutationLock) {
            List<DriveNode> nodes = readNodes(ownerId);
            DriveNode node = requireNode(nodes, id);
            if (node.isTrashed()) {
                return;
            }
            Instant now = Instant.now();
            for (DriveNode candidate : subtree(nodes, id)) {
                if (!candidate.isTrashed()) {
                    writeNode(candidate.trashed(now));
                }
            }
        }
    }

    public void restore(UUID ownerId, UUID id) {
        synchronized (mutationLock) {
            List<DriveNode> nodes = readNodes(ownerId);
            Map<UUID, DriveNode> byId = byId(nodes);
            DriveNode node = requireNode(nodes, id);
            if (!node.isTrashed()) {
                return;
            }
            if (node.parentId() != null) {
                DriveNode parent = byId.get(node.parentId());
                if (parent != null && parent.isTrashed()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Сначала восстановите родительскую папку");
                }
            }
            for (DriveNode candidate : subtree(nodes, id)) {
                writeNode(candidate.restored());
            }
        }
    }

    /** Окончательное удаление: только из корзины, иначе случайный DELETE терял бы данные. */
    public void purge(UUID ownerId, UUID id) {
        synchronized (mutationLock) {
            List<DriveNode> nodes = readNodes(ownerId);
            DriveNode node = requireNode(nodes, id);
            if (!node.isTrashed()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Файл или папка сначала должны попасть в корзину");
            }
            List<DriveNode> doomed = subtree(nodes, id);
            Set<UUID> doomedIds = doomed.stream().map(DriveNode::id).collect(Collectors.toSet());
            try {
                for (DriveNode candidate : doomed) {
                    storage.deleteNode(ownerId, candidate.id());
                }
                revokeSharesOf(ownerId, doomedIds);
                Set<String> stillReferenced = storage.listAllNodes().stream()
                        .filter(other -> other.kind() == DriveNode.Kind.FILE && other.sha256() != null)
                        .map(DriveNode::sha256)
                        .collect(Collectors.toSet());
                doomed.stream()
                        .filter(candidate -> candidate.kind() == DriveNode.Kind.FILE)
                        .map(DriveNode::sha256)
                        .distinct()
                        .filter(sha -> sha != null && !stillReferenced.contains(sha))
                        .forEach(sha -> storage.blobs().deleteBlob(sha));
            } catch (IOException e) {
                throw internal("Не удалось окончательно удалить", e);
            }
        }
    }

    public Download download(UUID ownerId, UUID id) {
        DriveNode node = requireNode(readNodes(ownerId), id);
        requireNotTrashed(node);
        if (node.kind() != DriveNode.Kind.FILE) {
            throw badRequest("Папку нельзя скачать как файл");
        }
        requireClean(node);
        Resource resource = storage.openContent(node)
                .orElseThrow(() -> notFound("Содержимое файла не найдено"));
        return new Download(node, resource);
    }

    public ShareCreated share(UUID ownerId, UUID id, Integer expiresInDays) {
        synchronized (mutationLock) {
            DriveNode node = requireNode(readNodes(ownerId), id);
            requireNotTrashed(node);
            if (node.kind() != DriveNode.Kind.FILE) {
                throw badRequest("Ссылку можно выдать только файлу");
            }
            Instant expiresAt = null;
            if (expiresInDays != null) {
                if (expiresInDays < 1 || expiresInDays > 365) {
                    throw badRequest("Срок действия ссылки должен быть от 1 до 365 дней");
                }
                expiresAt = Instant.now().plus(Duration.ofDays(expiresInDays));
            }
            String token = newToken();
            DriveShare share = new DriveShare(UUID.randomUUID(), node.id(), ownerId,
                    sha256Hex(token), Instant.now(), expiresAt, null);
            try {
                storage.saveShare(share);
            } catch (IOException e) {
                throw internal("Не удалось создать ссылку", e);
            }
            return new ShareCreated(share, token);
        }
    }

    public List<DriveShare> shares(UUID ownerId, UUID id) {
        requireNode(readNodes(ownerId), id);
        try {
            return storage.listShares(ownerId).stream()
                    .filter(share -> share.nodeId().equals(id))
                    .sorted(Comparator.comparing(DriveShare::createdAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw internal("Не удалось прочитать ссылки", e);
        }
    }

    public void revokeShare(UUID ownerId, UUID shareId) {
        synchronized (mutationLock) {
            try {
                DriveShare share = storage.listShares(ownerId).stream()
                        .filter(candidate -> candidate.id().equals(shareId))
                        .findFirst()
                        .orElseThrow(() -> notFound("Ссылка не найдена"));
                if (share.isRevoked()) {
                    return;
                }
                storage.saveShare(new DriveShare(share.id(), share.nodeId(), share.ownerId(),
                        share.tokenHash(), share.createdAt(), share.expiresAt(), Instant.now()));
            } catch (IOException e) {
                throw internal("Не удалось отозвать ссылку", e);
            }
        }
    }

    /**
     * Публичное скачивание по ссылке: без JWT, но с проверкой отзыва, срока и корзины.
     * Одинаковый ответ для несуществующей, отозванной и просроченной ссылки не даёт
     * перебирать токены по разнице в ошибках.
     */
    public Download downloadByToken(String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            throw notFound("Ссылка недействительна");
        }
        DriveShare share;
        try {
            share = storage.findShare(sha256Hex(token))
                    .orElseThrow(() -> notFound("Ссылка недействительна"));
        } catch (IOException e) {
            throw internal("Не удалось проверить ссылку", e);
        }
        if (!share.isActive(Instant.now())) {
            throw notFound("Ссылка недействительна");
        }
        DriveNode node = findNodeOrNull(share.ownerId(), share.nodeId());
        if (node == null || node.isTrashed() || node.kind() != DriveNode.Kind.FILE) {
            throw notFound("Ссылка недействительна");
        }
        requireClean(node);
        Resource resource = storage.openContent(node)
                .orElseThrow(() -> notFound("Ссылка недействительна"));
        return new Download(node, resource);
    }

    private void revokeSharesOf(UUID ownerId, Set<UUID> nodeIds) throws IOException {
        for (DriveShare share : storage.listShares(ownerId)) {
            if (nodeIds.contains(share.nodeId()) && !share.isRevoked()) {
                storage.deleteShare(share);
            }
        }
    }

    private DriveNode findNodeOrNull(UUID ownerId, UUID id) {
        return readNodes(ownerId).stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    private List<DriveNode> readNodes(UUID ownerId) {
        try {
            return storage.listNodes(ownerId);
        } catch (IOException e) {
            throw internal("Не удалось прочитать диск", e);
        }
    }

    private void writeNode(DriveNode node) {
        try {
            storage.saveNode(node);
        } catch (IOException e) {
            throw internal("Не удалось сохранить изменения", e);
        }
    }

    private Map<UUID, DriveNode> byId(List<DriveNode> nodes) {
        Map<UUID, DriveNode> map = new HashMap<>();
        nodes.forEach(node -> map.put(node.id(), node));
        return map;
    }

    private boolean sameParent(UUID candidate, UUID expected) {
        return expected == null ? candidate == null : expected.equals(candidate);
    }

    /** В корзине показываем только верхний уровень: удалённые вместе с папкой файлы не дублируются. */
    private boolean inTrashRoot(DriveNode node, Map<UUID, DriveNode> byId) {
        if (node.parentId() == null) {
            return true;
        }
        DriveNode parent = byId.get(node.parentId());
        return parent == null || !parent.isTrashed();
    }

    private List<DriveNode> subtree(List<DriveNode> nodes, UUID rootId) {
        Map<UUID, DriveNode> byId = byId(nodes);
        Map<UUID, List<DriveNode>> children = nodes.stream()
                .filter(node -> node.parentId() != null)
                .collect(Collectors.groupingBy(DriveNode::parentId));
        List<DriveNode> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            DriveNode node = byId.get(current);
            if (node != null) {
                result.add(node);
            }
            children.getOrDefault(current, List.of()).forEach(child -> queue.add(child.id()));
        }
        return result;
    }

    private boolean isDescendant(List<DriveNode> nodes, UUID candidateId, UUID ancestorId) {
        Map<UUID, UUID> parentOf = nodes.stream()
                .filter(node -> node.parentId() != null)
                .collect(Collectors.toMap(DriveNode::id, DriveNode::parentId, (a, b) -> a));
        UUID cursor = candidateId;
        Set<UUID> guard = new HashSet<>();
        while (cursor != null && guard.add(cursor)) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            cursor = parentOf.get(cursor);
        }
        return false;
    }

    private DriveNode requireNode(List<DriveNode> nodes, UUID id) {
        return nodes.stream()
                .filter(node -> node.id().equals(id))
                .findFirst()
                .orElseThrow(() -> notFound("Объект не найден"));
    }

    private void requireFolder(List<DriveNode> nodes, UUID parentId) {
        if (parentId == null) {
            return;
        }
        DriveNode parent = requireNode(nodes, parentId);
        if (!parent.isFolder()) {
            throw badRequest("Родитель должен быть папкой");
        }
        requireNotTrashed(parent);
    }

    private void requireNotTrashed(DriveNode node) {
        if (node.isTrashed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Объект находится в корзине");
        }
    }

    private void requireClean(DriveNode node) {
        if ("INFECTED".equals(node.avStatus()) || (avRequired && !"CLEAN".equals(node.avStatus()))) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "Файл недоступен до успешной антивирусной проверки");
        }
    }

    private void requireFreeName(List<DriveNode> nodes, UUID parentId, String name, UUID selfId) {
        String normalized = name.toLowerCase(Locale.ROOT);
        boolean taken = nodes.stream().anyMatch(node ->
                !node.isTrashed()
                        && sameParent(node.parentId(), parentId)
                        && !node.id().equals(selfId)
                        && node.name().toLowerCase(Locale.ROOT).equals(normalized));
        if (taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "В этой папке уже есть объект с именем «" + name + "»");
        }
    }

    private ClamAvClient.ScanResult scan(ChatFileStorage.StagedUpload staged) {
        try (InputStream input = java.nio.file.Files.newInputStream(staged.path())) {
            return clam.scan(input);
        } catch (IOException e) {
            return new ClamAvClient.ScanResult(ClamAvClient.Result.ERROR, e.getMessage());
        }
    }

    private String normalizeName(String original) {
        if (original == null || original.isBlank()) {
            throw badRequest("Имя не может быть пустым");
        }
        String normalized = original.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        StringBuilder cleaned = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .forEach(cleaned::appendCodePoint);
        String name = cleaned.toString().trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw badRequest("Недопустимое имя");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw badRequest("Имя длиннее " + MAX_NAME_LENGTH + " символов");
        }
        if (name.matches(".*[<>:\"/\\\\|?*].*")) {
            throw badRequest("Имя содержит недопустимые символы");
        }
        if (name.endsWith(".") || name.endsWith(" ")) {
            throw badRequest("Имя не может заканчиваться точкой или пробелом");
        }
        String base = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (WINDOWS_RESERVED.contains(base.toUpperCase(Locale.ROOT))) {
            throw badRequest("Это имя зарезервировано Windows");
        }
        return name;
    }

    private String normalizeContentType(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+") ? value : null;
    }

    /**
     * Тип содержимого для скачивания. HTML/SVG никогда не помечаем как активный контент:
     * даже если браузер проигнорирует Content-Disposition, он не выполнит файл на нашем origin.
     */
    private String detectContentType(String declared, String fileName) {
        String detected = MediaTypeFactory.getMediaType(fileName)
                .map(MediaType::toString)
                .orElse(declared);
        if (detected == null) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        String lower = detected.toLowerCase(Locale.ROOT);
        if (lower.startsWith("text/html") || lower.startsWith("application/xhtml")
                || lower.startsWith("image/svg")) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        return lower;
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException internal(String message, Exception cause) {
        log.warn("{}: {}", message, cause.getMessage());
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
