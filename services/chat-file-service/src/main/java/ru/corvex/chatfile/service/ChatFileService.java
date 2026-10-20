package ru.corvex.chatfile.service;

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
import ru.corvex.chatfile.client.ChatMembershipClient;
import ru.corvex.chatfile.domain.StoredChatFile;
import ru.corvex.chatfile.security.ChatFilePrincipal;
import ru.corvex.chatfile.storage.ChatFileStorage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Загрузка и выдача вложений чата.
 *
 * <p>Загрузить файл может только участник комнаты с правами записи; скачать — только актуальный
 * участник той же комнаты. Проверка повторяется на каждую операцию, поэтому удаление из комнаты
 * немедленно отзывает доступ к её файлам, а сам fileRef не является публичной ссылкой.</p>
 */
@Service
public class ChatFileService {

    private static final Logger log = LoggerFactory.getLogger(ChatFileService.class);
    private static final int MAX_FILE_NAME_LENGTH = 255;

    private final ChatMembershipClient memberships;
    private final ChatFileStorage storage;
    private final ClamAvClient clam;
    private final long maxBytes;
    private final boolean avRequired;

    public ChatFileService(
            ChatMembershipClient memberships,
            ChatFileStorage storage,
            ClamAvClient clam,
            @Value("${corvex.files.max-bytes:${CORVEX_CHAT_FILE_MAX_BYTES:26214400}}") long maxBytes,
            @Value("${corvex.files.av-required:${CORVEX_CHAT_FILE_AV_REQUIRED:true}}") boolean avRequired) {
        this.memberships = memberships;
        this.storage = storage;
        this.clam = clam;
        this.maxBytes = Math.max(1, maxBytes);
        this.avRequired = avRequired;
    }

    public record Download(StoredChatFile metadata, Resource resource) {
    }

    public StoredChatFile upload(ChatFilePrincipal principal, UUID roomId, MultipartFile file) {
        principal.require(ChatFilePrincipal.READ);
        principal.require(ChatFilePrincipal.WRITE);
        principal.require(ChatFilePrincipal.SEND_FILES);
        if (roomId == null) {
            throw badRequest("Не указана комната для файла");
        }
        if (file == null || file.isEmpty()) {
            throw badRequest("Файл не выбран или пуст");
        }

        ChatMembershipClient.Membership membership = memberships.requireMember(roomId, principal.userId());
        requireWritable(principal, membership);

        String fileName = normalizeFileName(file.getOriginalFilename());
        String declaredContentType = normalizeContentType(file.getContentType());
        ChatFileStorage.StagedUpload staged = null;
        try (InputStream input = file.getInputStream()) {
            staged = storage.stage(input, maxBytes);
            if (staged.sizeBytes() == 0) {
                throw badRequest("Файл не выбран или пуст");
            }

            ClamAvClient.ScanResult scan = scan(staged);
            StoredChatFile.AvStatus avStatus = switch (scan.result()) {
                case CLEAN -> StoredChatFile.AvStatus.CLEAN;
                case INFECTED -> throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Файл отклонён антивирусом");
                case ERROR -> {
                    if (avRequired) {
                        log.warn("ClamAV scan failed for upload to room {}: {}", roomId, scan.signature());
                        throw new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Антивирус временно недоступен, файл не опубликован");
                    }
                    yield StoredChatFile.AvStatus.ERROR;
                }
            };

            StoredChatFile metadata = new StoredChatFile(
                    UUID.randomUUID(),
                    roomId,
                    principal.userId(),
                    fileName,
                    detectContentType(staged.prefix(), declaredContentType, fileName),
                    staged.sizeBytes(),
                    staged.sha256(),
                    avStatus,
                    scan.signature(),
                    Instant.now());

            storage.publishBlob(staged);
            staged = null;
            storage.writeMetadata(metadata);
            return metadata;
        } catch (ChatFileStorage.FileTooLargeException e) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Файл превышает допустимый размер " + e.maxBytes() + " байт");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            log.warn("Chat file upload failed for room {}: {}", roomId, e.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось сохранить файл");
        } finally {
            storage.deleteStaged(staged);
        }
    }

    public Download download(ChatFilePrincipal principal, String fileRef) {
        principal.require(ChatFilePrincipal.READ);
        UUID id = parseFileRef(fileRef);
        StoredChatFile metadata;
        try {
            metadata = storage.loadMetadata(id)
                    .orElseThrow(() -> notFound("Файл не найден"));
        } catch (IOException e) {
            log.warn("Chat file metadata read failed for {}: {}", id, e.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось прочитать метаданные файла");
        }

        memberships.requireMember(metadata.roomId(), principal.userId());
        if (metadata.avStatus() == StoredChatFile.AvStatus.INFECTED
                || (avRequired && metadata.avStatus() != StoredChatFile.AvStatus.CLEAN)) {
            throw new ResponseStatusException(
                    HttpStatus.LOCKED,
                    "Файл недоступен до успешной антивирусной проверки");
        }
        Resource resource = storage.openContent(metadata)
                .orElseThrow(() -> notFound("Содержимое файла не найдено"));
        return new Download(metadata, resource);
    }

    private ClamAvClient.ScanResult scan(ChatFileStorage.StagedUpload staged) {
        try (InputStream input = Files.newInputStream(staged.path())) {
            return clam.scan(input);
        } catch (IOException e) {
            return new ClamAvClient.ScanResult(ClamAvClient.Result.ERROR, e.getMessage());
        }
    }

    private void requireWritable(ChatFilePrincipal principal, ChatMembershipClient.Membership membership) {
        if ("READONLY".equals(membership.role())) {
            throw forbidden("Роль участника не позволяет писать в эту комнату");
        }
        if (membership.mutedNow()) {
            throw forbidden("Пользователь временно не может писать в эту комнату");
        }
        boolean canModerate = principal.has(ChatFilePrincipal.MODERATE) || membership.roomModerator();
        if (membership.roomReadOnly() && !canModerate) {
            throw forbidden("Комната доступна только для чтения");
        }
    }

    private UUID parseFileRef(String fileRef) {
        if (fileRef != null) {
            try {
                return UUID.fromString(fileRef);
            } catch (IllegalArgumentException ignored) {
                // Одинаковый 404 для битого и несуществующего ref не раскрывает формат чужих ссылок.
            }
        }
        throw notFound("Файл не найден");
    }

    private String normalizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "file";
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
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            return "file";
        }
        return name.length() <= MAX_FILE_NAME_LENGTH ? name : name.substring(0, MAX_FILE_NAME_LENGTH);
    }

    private String normalizeContentType(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+") ? value : null;
    }

    private String detectContentType(byte[] prefix, String declared, String fileName) {
        String detected = detectByMagic(prefix);
        if (detected != null && !"application/zip".equals(detected)) {
            return detected;
        }
        if (isSvg(prefix)) {
            return "image/svg+xml";
        }
        if (declared != null) {
            if (declared.startsWith("image/")) {
                // Недоверенный image/* без сигнатуры не публикуем как изображение: превью могло бы
                // показать активный/подменённый контент под видом картинки.
                return MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }
            if ("text/html".equals(declared) || "application/xhtml+xml".equals(declared)) {
                return MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }
            return declared;
        }
        if (detected != null) {
            return detected;
        }
        return MediaTypeFactory.getMediaType(fileName)
                .map(MediaType::toString)
                .filter(type -> !type.startsWith("image/"))
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private String detectByMagic(byte[] p) {
        if (p == null) {
            return null;
        }
        if (startsWith(p, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(p, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(p, 'G', 'I', 'F', '8', '7', 'a') || startsWith(p, 'G', 'I', 'F', '8', '9', 'a')) {
            return "image/gif";
        }
        if (p.length >= 12
                && startsWith(p, 'R', 'I', 'F', 'F')
                && p[8] == 'W' && p[9] == 'E' && p[10] == 'B' && p[11] == 'P') {
            return "image/webp";
        }
        if (startsWith(p, '%', 'P', 'D', 'F', '-')) {
            return "application/pdf";
        }
        if (startsWith(p, 'P', 'K', 0x03, 0x04)) {
            return "application/zip";
        }
        return null;
    }

    private boolean isSvg(byte[] prefix) {
        if (prefix == null || prefix.length == 0) {
            return false;
        }
        String head = new String(prefix, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return head.contains("<svg");
    }

    private boolean startsWith(byte[] prefix, int... expected) {
        if (prefix.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i += 1) {
            if ((prefix[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    private ResponseStatusException forbidden(String detail) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, detail);
    }

    private ResponseStatusException notFound(String detail) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, detail);
    }
}
