package ru.corvex.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.corvex.chat.api.ChatDtos;

import java.time.Instant;
import java.util.UUID;

/**
 * Публикация событий комнаты в STOMP-брокер.
 *
 * <p>События, связанные с записью в chat_db, отправляются после commit: подписчик не должен
 * получить сообщение по WebSocket раньше, чем оно станет доступно повторному REST-запросу.</p>
 */
@Component
public class ChatEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatEventPublisher.class);

    public static final String MESSAGE_CREATED = "MESSAGE_CREATED";
    public static final String MESSAGE_UPDATED = "MESSAGE_UPDATED";
    public static final String MESSAGE_DELETED = "MESSAGE_DELETED";
    public static final String MESSAGE_READ = "MESSAGE_READ";
    public static final String TYPING = "TYPING";
    public static final String MENTION = "MENTION";

    private final SimpMessagingTemplate broker;

    public ChatEventPublisher(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    public void publishMessageCreated(ChatDtos.MessageView message, UUID actorId) {
        publishRoomEvent(MESSAGE_CREATED, message, actorId);
    }

    public void publishMessageUpdated(ChatDtos.MessageView message, UUID actorId) {
        publishRoomEvent(MESSAGE_UPDATED, message, actorId);
    }

    public void publishMessageDeleted(ChatDtos.MessageView message, UUID actorId) {
        publishRoomEvent(MESSAGE_DELETED, message, actorId);
    }

    public void publishRoomEvent(String type, ChatDtos.MessageView message, UUID actorId) {
        if (message == null || message.roomId() == null) {
            return;
        }
        ChatDtos.ChatEventView event = new ChatDtos.ChatEventView(
                type, message.roomId(), message, actorId, message.id(), Instant.now());
        afterCommit(() -> broker.convertAndSend(roomTopic(message.roomId()), event));
    }

    public void publishRead(UUID roomId, UUID actorId, UUID messageId) {
        if (roomId == null) {
            return;
        }
        ChatDtos.ChatEventView event = new ChatDtos.ChatEventView(
                MESSAGE_READ, roomId, null, actorId, messageId, Instant.now());
        afterCommit(() -> broker.convertAndSend(roomTopic(roomId), event));
    }

    /** Typing эфемерен и не участвует в транзакции БД, поэтому отправляется сразу. */
    public void publishTyping(UUID roomId, UUID actorId) {
        if (roomId == null || actorId == null) {
            return;
        }
        ChatDtos.ChatEventView event = new ChatDtos.ChatEventView(
                TYPING, roomId, null, actorId, null, Instant.now());
        safeSend(() -> broker.convertAndSend(roomTopic(roomId), event));
    }

    public void publishMention(ChatDtos.MessageView message, UUID mentionedUserId) {
        if (message == null || mentionedUserId == null) {
            return;
        }
        ChatDtos.ChatEventView event = new ChatDtos.ChatEventView(
                MENTION, message.roomId(), message, message.authorId(), message.id(), Instant.now());
        afterCommit(() -> broker.convertAndSendToUser(
                mentionedUserId.toString(), "/queue/notifications", event));
    }

    public void afterCommit(Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeSend(action);
                }
            });
        } else {
            safeSend(action);
        }
    }

    private String roomTopic(UUID roomId) {
        return "/topic/rooms/" + roomId;
    }

    private void safeSend(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // Сообщение уже сохранено в PostgreSQL; потеря realtime-уведомления не должна
            // превращаться в потерю данных. Клиент восстановит состояние REST-историей.
            log.warn("Failed to publish chat event: {}", e.getMessage());
        }
    }
}
