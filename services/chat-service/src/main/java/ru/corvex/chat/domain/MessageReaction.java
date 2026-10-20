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
import java.util.UUID;

/** Одна эмодзи-реакция одного пользователя на одно сообщение. */
@Entity
@Table(name = "message_reactions")
@Getter
@Setter
@NoArgsConstructor
public class MessageReaction {

    @EmbeddedId
    private Id id;

    public MessageReaction(UUID messageId, UUID userId, String emoji) {
        this.id = new Id(messageId, userId, emoji);
    }

    public UUID getMessageId() {
        return id == null ? null : id.messageId;
    }

    public UUID getUserId() {
        return id == null ? null : id.userId;
    }

    public String getEmoji() {
        return id == null ? null : id.emoji;
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

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(nullable = false, length = 16)
        private String emoji;
    }
}
