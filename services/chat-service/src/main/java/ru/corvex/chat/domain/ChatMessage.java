package ru.corvex.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Persisted-сообщение чата. Удаление мягкое: API маскирует тело после deletedAt. */
@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind = Kind.TEXT;

    @Column(name = "body_markdown", columnDefinition = "TEXT")
    private String bodyMarkdown;

    @Column(name = "thread_root_id")
    private UUID threadRootId;

    @Column(name = "file_ref", length = 256)
    private String fileRef;

    @Column(nullable = false)
    private boolean pinned;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Клиентский ключ идемпотентности повторной отправки после reconnect. */
    @Column(name = "client_message_id", length = 128)
    private String clientMessageId;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    @PrePersist
    void initCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (kind == null) {
            kind = Kind.TEXT;
        }
    }

    public enum Kind {
        TEXT,
        FILE,
        IMAGE,
        VOICE,
        VIDEO,
        SYSTEM
    }
}
