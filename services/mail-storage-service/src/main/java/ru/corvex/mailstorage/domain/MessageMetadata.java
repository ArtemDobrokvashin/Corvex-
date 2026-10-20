package ru.corvex.mailstorage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Метаданные письма. Тело (.eml целиком) лежит в Maildir на ФС (path), вложения — отдельно
 * в контентно-адресуемом хранилище по SHA-256 (дедупликация). В БД — только то, по чему
 * строятся списки, сортировки и IMAP-флаги: цель — список папки отдаётся одним SQL-запросом
 * без чтения файлов (&lt; 100 мс на папку из 50 тыс. писем при индексах ниже).
 */
@Entity
@Table(name = "message_metadata",
        indexes = {
                @Index(name = "idx_msg_folder_uid", columnList = "folder_id, uid"),
                @Index(name = "idx_msg_owner_date", columnList = "owner_id, internal_date DESC"),
                @Index(name = "idx_msg_message_id", columnList = "message_id_header")
        },
        uniqueConstraints = @UniqueConstraint(name = "uq_folder_uid", columnNames = {"folder_id", "uid"}))
@Getter
@Setter
@NoArgsConstructor
public class MessageMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "folder_id", nullable = false)
    private MailFolder folder;

    /** IMAP UID внутри папки: монотонно растёт, никогда не переиспользуется (RFC 3501 §2.3.1.1). */
    @Column(nullable = false)
    private long uid;

    /** MODSEQ письма (RFC 7162 CONDSTORE): меняется при создании и при изменении флагов/перемещении. */
    @Column(nullable = false)
    private long modseq = 1;

    @Column(name = "message_id_header", length = 998)
    private String messageIdHeader;

    /**
     * Идентификатор сырого spool-объекта этапа 2. Вместе с owner_id это ключ идемпотентности
     * доставки: повторное событие RabbitMQ для того же получателя не создаёт второй экземпляр письма.
     */
    @Column(name = "raw_ref", length = 64)
    private String rawRef;

    @Column(name = "sender", nullable = false, length = 320)
    private String sender;

    @Column(nullable = false, length = 998)
    private String subject;

    /** Сниппет тела (первые ~200 символов plain text) для списка без открытия письма. */
    @Column(length = 512)
    private String snippet;

    @Column(name = "internal_date", nullable = false)
    private Instant internalDate;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Относительный путь .eml в Maildir, напр. ivanov@corp.local/INBOX/new/1718...eml */
    @Column(name = "maildir_path", nullable = false, length = 1024)
    private String maildirPath;

    // IMAP-системные флаги отдельными колонками (не битмаской) — индексы и читаемые запросы важнее экономии байт.
    private boolean seen;
    private boolean answered;
    private boolean flagged;
    private boolean draft;
    private boolean deleted;

    /** Итог спам-скоринга SpamAssassin; NULL — проверка ещё не завершена (письмо в очереди фильтрации). */
    @Column(name = "spam_score")
    private Double spamScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "av_status", nullable = false, length = 16)
    private AvStatus avStatus = AvStatus.PENDING;

    public enum AvStatus {PENDING, CLEAN, INFECTED, ERROR}
}
