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

/** Явное упоминание участника комнаты в сообщении. */
@Entity
@Table(name = "message_mentions")
@Getter
@Setter
@NoArgsConstructor
public class MessageMention {

    @EmbeddedId
    private Id id;

    public MessageMention(UUID messageId, UUID mentionedUserId) {
        this.id = new Id(messageId, mentionedUserId);
    }

    public UUID getMessageId() {
        return id == null ? null : id.messageId;
    }

    public UUID getMentionedUserId() {
        return id == null ? null : id.mentionedUserId;
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

        @Column(name = "mentioned_user_id", nullable = false)
        private UUID mentionedUserId;
    }
}
