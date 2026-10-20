package ru.corvex.mailstorage.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Контракт внутреннего протокольного API (этап 3): mail-imap-service и mail-pop3-service
 * не имеют своей БД и не трогают Maildir напрямую — все операции ящика (папки, UID,
 * флаги, expunge, append, copy/move, сырое тело) идут сюда под X-Corvex-Internal-Token.
 * Идентичность пользователя протокольный сервис подтверждает в auth-service
 * (verify-password) и передаёт userId в пути; хранилище дополнительно проверяет,
 * что каждая папка/письмо принадлежит именно этому ownerId.
 */
public final class ProtocolDtos {

    private ProtocolDtos() {
    }

    /** Папка в терминах IMAP: UIDNEXT/UIDVALIDITY/HIGHESTMODSEQ + счётчики для SELECT/STATUS. */
    public record FolderInfo(UUID id, UUID parentId, String name, String specialUse,
                             long uidNext, long uidValidity, long highestModseq,
                             long messageCount, long unseenCount) {
    }

    public record CreateFolderRequest(String name, UUID parentId) {
    }

    /**
     * IMAP RENAME может одновременно менять имя и родителя. parentId=null при moveToRoot=false
     * означает «родителя не трогать» (совместимость со старым клиентом); перенос в корень — явно.
     */
    public record RenameFolderRequest(String name, UUID parentId, boolean moveToRoot) {
    }

    /** Метаданные письма без тела: POP3 LIST/UIDL и IMAP FETCH (ENVELOPE строится из сырого тела). */
    public record MessageMeta(UUID id, long uid, long modseq, String messageIdHeader, String sender,
                              String subject, Instant internalDate, long sizeBytes,
                              boolean seen, boolean answered, boolean flagged,
                              boolean draft, boolean deleted) {
    }

    /** Частичное обновление флагов: null = не трогать (IMAP STORE +/-FLAGS сводится сюда адаптером). */
    public record FlagsUpdate(Boolean seen, Boolean answered, Boolean flagged,
                              Boolean draft, Boolean deleted) {
    }

    /** Пустой список uids = классический EXPUNGE всех \Deleted; непустой = UID EXPUNGE (RFC 4315). */
    public record ExpungeRequest(List<Long> uids) {
    }

    public record ExpungeResponse(List<Long> expungedUids) {
    }

    public record AppendResponse(UUID messageId, long uid, long modseq) {
    }

    public record CopyRequest(List<Long> uids, UUID targetFolderId, boolean move) {
    }

    public record CopyResponse(List<Long> sourceUids, List<Long> targetUids) {
    }

    /** Квота ящика для IMAP QUOTA (RFC 2087): quotaBytes=0 означает «без лимита». */
    public record QuotaView(long usedBytes, long quotaBytes, long messageCount) {
    }
}
