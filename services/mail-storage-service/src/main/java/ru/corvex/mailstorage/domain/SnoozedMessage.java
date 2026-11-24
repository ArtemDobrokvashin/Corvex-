package ru.corvex.mailstorage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * «Отложить до…» (snooze). Письмо не покидает папку и не меняет UID — строка
 * лишь скрывает его из списка до {@code wakeAt}. Так отмена не требует
 * восстанавливать номер письма, а IMAP-клиенты видят ровно то же письмо.
 *
 * <p>Запись удаляется, когда срок наступил: планировщик возвращает письмо в
 * список, и «спящих» строк для него больше нет.
 */
@Entity
@Table(name = "webmail_snoozed_messages")
@Getter
@Setter
@NoArgsConstructor
public class SnoozedMessage {

    @Id
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "folder_id", nullable = false)
    private UUID folderId;

    @Column(name = "wake_at", nullable = false)
    private Instant wakeAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public SnoozedMessage(UUID messageId, UUID ownerId, UUID folderId, Instant wakeAt) {
        this.messageId = messageId;
        this.ownerId = ownerId;
        this.folderId = folderId;
        this.wakeAt = wakeAt;
    }
}
