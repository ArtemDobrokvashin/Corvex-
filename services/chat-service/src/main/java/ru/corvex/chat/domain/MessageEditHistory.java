package ru.corvex.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Предыдущая редакция сообщения для аудита/модерации. */
@Entity
@Table(name = "message_edit_history")
@Getter
@Setter
@NoArgsConstructor
public class MessageEditHistory {

    @EmbeddedId
    private Id id;

    @Column(name = "old_body", nullable = false, columnDefinition = "TEXT")
    private String oldBody;

    public MessageEditHistory(UUID messageId, String oldBody, Instant editedAt) {
        this.id = new Id(messageId, editedAt);
        this.oldBody = oldBody == null ? "" : oldBody;
    }

    public UUID getMessageId() {
        return id == null ? null : id.messageId;
    }

    public Instant getEditedAt() {
        return id == null ? null : id.editedAt;
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Column(name = "message_id", nullable = false)
        private UUID messageId;

        @Column(name = "edited_at", nullable = false)
        private Instant editedAt;
    }
}
