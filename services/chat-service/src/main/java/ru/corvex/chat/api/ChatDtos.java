package ru.corvex.chat.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.corvex.chat.domain.ChatMember;
import ru.corvex.chat.domain.ChatMessage;
import ru.corvex.chat.domain.ChatRoom;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Публичные контракты chat-service.
 *
 * <p>Gateway не срезает префикс {@code /api/v1/chat/**}; REST-контроллер поэтому будет
 * замаплен на полный публичный путь. Эти DTO переиспользуются STOMP-слоем и фронтендом,
 * чтобы REST-история и realtime-события не расходились по форме сообщения.</p>
 */
public final class ChatDtos {

    private ChatDtos() {
    }

    /** Минимальная запись корпоративного каталога для выбора собеседника/участников. */
    public record DirectoryUserView(
            UUID id,
            String username,
            String email,
            String displayName,
            String department,
            String status,
            String presence) {
    }

    public record MemberView(
            UUID userId,
            ChatMember.Role role,
            Instant mutedUntil,
            UUID lastReadMessageId,
            Instant joinedAt) {
    }

    public record ReactionView(
            String emoji,
            long count,
            List<UUID> userIds) {

        public ReactionView {
            userIds = userIds == null ? List.of() : List.copyOf(userIds);
        }
    }

    public record MessageView(
            UUID id,
            UUID roomId,
            UUID authorId,
            ChatMessage.Kind kind,
            String bodyMarkdown,
            UUID threadRootId,
            String fileRef,
            boolean pinned,
            Instant editedAt,
            boolean deleted,
            String clientMessageId,
            List<UUID> mentionedUserIds,
            List<ReactionView> reactions,
            Instant createdAt) {

        public MessageView {
            mentionedUserIds = mentionedUserIds == null ? List.of() : List.copyOf(mentionedUserIds);
            reactions = reactions == null ? List.of() : List.copyOf(reactions);
        }
    }

    public record MessagePage(
            List<MessageView> items,
            boolean hasMore,
            UUID nextBefore) {

        public MessagePage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record RoomSummaryView(
            UUID id,
            ChatRoom.Kind kind,
            String title,
            UUID ownerId,
            boolean readOnly,
            List<UUID> memberIds,
            ChatMember.Role myRole,
            int memberCount,
            int unreadCount,
            int mentionCount,
            MessageView lastMessage,
            Instant createdAt,
            Instant lastMessageAt) {

        public RoomSummaryView {
            memberIds = memberIds == null ? List.of() : List.copyOf(memberIds);
        }
    }

    /** Единая оболочка STOMP-событий комнаты и личных уведомлений. */
    public record ChatEventView(
            String type,
            UUID roomId,
            MessageView message,
            UUID actorId,
            UUID messageId,
            Instant occurredAt) {
    }

    public record CreateRoomRequest(
            @NotNull ChatRoom.Kind kind,
            @Size(max = 256) String title,
            @NotNull @Size(max = 500) List<@NotNull UUID> memberIds,
            Boolean readOnly) {

        public CreateRoomRequest {
            memberIds = memberIds == null ? List.of() : memberIds;
        }
    }

    public record SendMessageRequest(
            @Size(max = 32_000) String bodyMarkdown,
            UUID threadRootId,
            @Size(max = 128) String clientMessageId,
            ChatMessage.Kind kind,
            @Size(max = 256) String fileRef,
            @Size(max = 100) List<@NotNull UUID> mentionedUserIds) {

        public SendMessageRequest {
            mentionedUserIds = mentionedUserIds == null ? List.of() : mentionedUserIds;
        }
    }

    public record EditMessageRequest(
            @NotBlank @Size(max = 32_000) String bodyMarkdown) {
    }

    public record ReactionRequest(
            @NotBlank @Size(max = 16) String emoji) {
    }

    public record ReadRequest(
            @NotNull UUID lastReadMessageId) {
    }

    public record AddMembersRequest(
            @NotNull @Size(min = 1, max = 500) List<@NotNull UUID> memberIds) {

        public AddMembersRequest {
            memberIds = memberIds == null ? List.of() : memberIds;
        }
    }

    public record PresenceRequest(
            @NotBlank @Size(max = 16) String status) {
    }

    /** Ответ internal endpoint для chat-file-service: только факт членства и роль, без содержимого чата. */
    public record MembershipInternalView(
            UUID roomId,
            UUID userId,
            boolean member,
            ChatMember.Role role,
            Instant mutedUntil,
            boolean roomReadOnly) {
    }
}
