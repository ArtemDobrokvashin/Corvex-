package ru.corvex.mailimap.model;

import ru.corvex.mailimap.client.ImapStorageClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Отображение IMAP-имён ящиков (иерархия через '/', RFC 9051 §5.1) на дерево папок
 * хранилища (parent_id + name). Соглашения Corvex:
 * <ul>
 *   <li>INBOX — имя, а не путь: регистронезависим, детей вида INBOX.x трактуем как
 *       обычные дочерние папки корня (клиенты с personal namespace "" это понимают);</li>
 *   <li>системные папки адресуются и по имени из БД (Sent/Drafts/...), и клиент может
 *       найти их по SPECIAL-USE в LIST — движок James сам добавит атрибуты из specialUse;</li>
 *   <li>modified UTF-7 (RFC 9051 §5.1.3) декодирует движок James до этого слоя: здесь
 *       уже обычный Unicode, поэтому русские имена папок сравниваются как есть,
 *       регистр для пользовательских папок сохраняется, сравнение — без учёта регистра,
 *       как принято у большинства серверов.</li>
 * </ul>
 */
public final class ImapFolderPaths {

    public static final char DELIMITER = '/';

    private ImapFolderPaths() {
    }

    /** Разбор пути "INBOX/Проекты/2026" на сегменты; пустые сегменты и хвостовые '/' отбрасываются. */
    public static List<String> segments(String imapPath) {
        if (imapPath == null || imapPath.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : imapPath.split("/")) {
            String seg = part.trim();
            if (!seg.isEmpty()) {
                out.add(seg);
            }
        }
        return out;
    }

    public static boolean isInboxPath(String imapPath) {
        List<String> segs = segments(imapPath);
        return segs.size() == 1 && "INBOX".equalsIgnoreCase(segs.get(0));
    }

    /**
     * Резолв IMAP-пути в папку хранилища по снимку дерева. INBOX ищется по specialUse/name
     * независимо от регистра; остальные сегменты — по цепочке родителей.
     */
    public static Optional<ImapStorageClient.FolderInfo> resolve(String imapPath,
                                                                   List<ImapStorageClient.FolderInfo> folders) {
        List<String> segs = segments(imapPath);
        if (segs.isEmpty()) {
            return Optional.empty();
        }
        ImapStorageClient.FolderInfo current = null;
        for (int i = 0; i < segs.size(); i++) {
            String seg = segs.get(i);
            UUID parentId = current == null ? null : current.id();
            ImapStorageClient.FolderInfo match = null;
            for (ImapStorageClient.FolderInfo f : folders) {
                boolean sameParent = parentId == null ? f.parentId() == null : parentId.equals(f.parentId());
                if (i == 0 && "INBOX".equalsIgnoreCase(seg)) {
                    if ("INBOX".equalsIgnoreCase(f.name()) || "INBOX".equalsIgnoreCase(f.specialUse())) {
                        match = f;
                        break;
                    }
                } else if (sameParent && f.name().equalsIgnoreCase(seg)) {
                    match = f;
                    break;
                }
            }
            if (match == null) {
                return Optional.empty();
            }
            current = match;
        }
        return Optional.ofNullable(current);
    }

    /** Обратное отображение для LIST: папка -> "INBOX/Проекты/2026" (INBOX всегда капсом, как принято). */
    public static String toImapPath(ImapStorageClient.FolderInfo folder,
                                    List<ImapStorageClient.FolderInfo> folders) {
        Map<UUID, ImapStorageClient.FolderInfo> byId = new HashMap<>();
        folders.forEach(f -> byId.put(f.id(), f));
        List<String> parts = new ArrayList<>();
        ImapStorageClient.FolderInfo current = folder;
        int guard = 0;
        while (current != null && guard++ < 64) { // защита от циклов в повреждённом дереве
            String name = ("INBOX".equalsIgnoreCase(current.name()) || "INBOX".equalsIgnoreCase(current.specialUse()))
                    && current.parentId() == null ? "INBOX" : current.name();
            parts.add(name);
            current = current.parentId() == null ? null : byId.get(current.parentId());
        }
        java.util.Collections.reverse(parts);
        return String.join(String.valueOf(DELIMITER), parts);
    }
}
