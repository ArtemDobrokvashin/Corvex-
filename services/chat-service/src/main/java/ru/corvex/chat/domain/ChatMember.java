package ru.corvex.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
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

/** Членство пользователя в комнате и его локальная роль/точка прочтения. */
@Entity
@Table(name = "chat_members")
@Getter
@Setter
@NoArgsConstructor
public class ChatMember {

    @EmbeddedId
    private Id id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role = Role.MEMBER;

    @Column(name = "muted_until")
    private Instant mutedUntil;

    @Column(name = "last_read_message_id")
    private UUID lastReadMessageId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    public ChatMember(UUID roomId, UUID userId, Role role) {
        this.id = new Id(roomId, userId);
        this.role = role;
    }

    public UUID getRoomId() {
        return id == null ? null : id.roomId;
    }

    public UUID getUserId() {
        return id == null ? null : id.userId;
    }

    public boolean isMuted(Instant now) {
        return mutedUntil != null && mutedUntil.isAfter(now);
    }

    @PrePersist
    void initJoinedAt() {
        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
        if (role == null) {
            role = Role.MEMBER;
        }
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

        @Column(name = "room_id", nullable = false)
        private UUID roomId;

        @Column(name = "user_id", nullable = false)
        private UUID userId;
    }

    public enum Role {
        OWNER,
        ADMIN,
        MEMBER,
        READONLY
    }
}
