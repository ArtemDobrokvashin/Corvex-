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

/** Комната чата: личный диалог, группа, канал или системный канал объявлений. */
@Entity
@Table(name = "chat_rooms")
@Getter
@Setter
@NoArgsConstructor
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    @Column(length = 256)
    private String title;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "read_only", nullable = false)
    private boolean readOnly;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    @PrePersist
    void initTimestamps() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastMessageAt == null) {
            lastMessageAt = createdAt;
        }
    }

    public enum Kind {
        DIRECT,
        GROUP,
        CHANNEL_PUBLIC,
        CHANNEL_PRIVATE,
        SYSTEM
    }
}
