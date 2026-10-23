package ru.corvex.chat.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.corvex.chat.api.ChatDtos;
import ru.corvex.chat.domain.ChatMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ChatEventPublisherTest {

    private static final UUID ROOM_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID AUTHOR_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID MENTIONED_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");

    @Mock private SimpMessagingTemplate broker;

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void publishesCreatedUpdatedDeletedAndReadEventsToRoomTopic() {
        ChatEventPublisher publisher = new ChatEventPublisher(broker);
        ChatDtos.MessageView message = message();

        publisher.publishMessageCreated(message, AUTHOR_ID);
        publisher.publishMessageUpdated(message, AUTHOR_ID);
        publisher.publishMessageDeleted(message, AUTHOR_ID);
        publisher.publishRead(ROOM_ID, AUTHOR_ID, MESSAGE_ID);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(broker, org.mockito.Mockito.times(4))
                .convertAndSend(eq("/topic/rooms/" + ROOM_ID), payload.capture());
        List<ChatDtos.ChatEventView> events = payload.getAllValues().stream()
                .map(ChatDtos.ChatEventView.class::cast).toList();
        assertEquals(List.of(
                ChatEventPublisher.MESSAGE_CREATED,
                ChatEventPublisher.MESSAGE_UPDATED,
                ChatEventPublisher.MESSAGE_DELETED,
                ChatEventPublisher.MESSAGE_READ), events.stream().map(ChatDtos.ChatEventView::type).toList());
        assertEquals(message, events.get(0).message());
        assertNull(events.get(3).message());
        assertEquals(MESSAGE_ID, events.get(3).messageId());
    }

    @Test
    void publishesTypingImmediatelyAndMentionToUserQueue() {
        ChatEventPublisher publisher = new ChatEventPublisher(broker);

        publisher.publishTyping(ROOM_ID, AUTHOR_ID);
        publisher.publishMention(message(), MENTIONED_ID);

        ArgumentCaptor<Object> typing = ArgumentCaptor.forClass(Object.class);
        verify(broker).convertAndSend(eq("/topic/rooms/" + ROOM_ID), typing.capture());
        assertEquals(ChatEventPublisher.TYPING,
                ((ChatDtos.ChatEventView) typing.getValue()).type());

        ArgumentCaptor<Object> mention = ArgumentCaptor.forClass(Object.class);
        verify(broker).convertAndSendToUser(
                eq(MENTIONED_ID.toString()), eq("/queue/notifications"), mention.capture());
        ChatDtos.ChatEventView event = (ChatDtos.ChatEventView) mention.getValue();
        assertEquals(ChatEventPublisher.MENTION, event.type());
        assertEquals(AUTHOR_ID, event.actorId());
    }

    @Test
    void nullInputsDoNothing() {
        ChatEventPublisher publisher = new ChatEventPublisher(broker);
        ChatDtos.MessageView noRoom = new ChatDtos.MessageView(
                MESSAGE_ID, null, AUTHOR_ID, ChatMessage.Kind.TEXT, "hello", null, null,
                false, null, false, "client", List.of(), List.of(), Instant.now());

        publisher.publishMessageCreated(null, AUTHOR_ID);
        publisher.publishRoomEvent("CUSTOM", noRoom, AUTHOR_ID);
        publisher.publishRead(null, AUTHOR_ID, MESSAGE_ID);
        publisher.publishTyping(null, AUTHOR_ID);
        publisher.publishTyping(ROOM_ID, null);
        publisher.publishMention(null, MENTIONED_ID);
        publisher.publishMention(message(), null);
        publisher.afterCommit(null);

        verifyNoInteractions(broker);
    }

    @Test
    void defersDatabaseEventsUntilTransactionCommit() {
        ChatEventPublisher publisher = new ChatEventPublisher(broker);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publishMessageCreated(message(), AUTHOR_ID);

        verify(broker, never()).convertAndSend(anyString(), any(Object.class));
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.get(0).afterCommit();
        verify(broker).convertAndSend(eq("/topic/rooms/" + ROOM_ID), any(Object.class));
    }

    @Test
    void brokerFailuresDoNotEscape() {
        doThrow(new IllegalStateException("broker down"))
                .when(broker).convertAndSend(anyString(), any(Object.class));
        ChatEventPublisher publisher = new ChatEventPublisher(broker);

        assertDoesNotThrow(() -> publisher.publishMessageCreated(message(), AUTHOR_ID));
        assertDoesNotThrow(() -> publisher.publishTyping(ROOM_ID, AUTHOR_ID));
        assertDoesNotThrow(() -> publisher.afterCommit(() -> {
            throw new IllegalStateException("boom");
        }));
    }

    private ChatDtos.MessageView message() {
        return new ChatDtos.MessageView(
                MESSAGE_ID, ROOM_ID, AUTHOR_ID, ChatMessage.Kind.TEXT, "hello", null, null,
                false, null, false, "client-1", List.of(), List.of(),
                Instant.parse("2026-10-20T09:00:00Z"));
    }
}
