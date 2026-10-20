package ru.corvex.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.chat.api.ChatDtos;
import ru.corvex.chat.client.AuthDirectoryClient;
import ru.corvex.chat.domain.ChatMember;
import ru.corvex.chat.domain.ChatMessage;
import ru.corvex.chat.domain.ChatRoom;
import ru.corvex.chat.domain.MessageMention;
import ru.corvex.chat.repository.ChatMemberRepository;
import ru.corvex.chat.repository.ChatMessageRepository;
import ru.corvex.chat.repository.ChatRoomRepository;
import ru.corvex.chat.repository.MessageEditHistoryRepository;
import ru.corvex.chat.repository.MessageMentionRepository;
import ru.corvex.chat.repository.MessageReactionRepository;
import ru.corvex.chat.security.ChatPrincipal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OTHER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID MESSAGE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Mock
    private ChatRoomRepository rooms;
    @Mock
    private ChatMemberRepository members;
    @Mock
    private ChatMessageRepository messages;
    @Mock
    private MessageEditHistoryRepository editHistory;
    @Mock
    private MessageReactionRepository reactions;
    @Mock
    private MessageMentionRepository mentions;
    @Mock
    private AuthDirectoryClient directory;
    @Mock
    private ChatEventPublisher events;

    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(rooms, members, messages, editHistory, reactions, mentions, directory, events);
        when(reactions.findAllById_MessageIdIn(any())).thenReturn(List.of());
        when(mentions.findAllById_MessageIdIn(any())).thenReturn(List.of());
    }

    @Test
    void sendMessageRejectsNonMember() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room(false)));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(principal(), ROOM_ID, request("hello", "client-1", List.of())));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void sendMessageWithSameClientIdReturnsExistingMessageWithoutDuplicateOrEvent() {
        ChatMessage existing = existingMessage();
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room(false)));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(membership(ChatMember.Role.MEMBER)));
        when(messages.findByRoomIdAndAuthorIdAndClientMessageId(ROOM_ID, USER_ID, "client-1"))
                .thenReturn(Optional.of(existing));

        ChatDtos.MessageView result = service.sendMessage(
                principal(), ROOM_ID, request("changed after reconnect", "client-1", List.of()));

        assertEquals(MESSAGE_ID, result.id());
        assertEquals("original", result.bodyMarkdown());
        assertEquals("client-1", result.clientMessageId());
        verify(messages, never()).saveAndFlush(any());
        verify(events, never()).publishMessageCreated(any(), any());
    }

    @Test
    void sendMessagePersistsMessageUpdatesRoomAndAuthorReadPositionAndPublishesEvents() {
        ChatRoom room = room(false);
        ChatMember membership = membership(ChatMember.Role.MEMBER);
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(membership));
        when(messages.findByRoomIdAndAuthorIdAndClientMessageId(ROOM_ID, USER_ID, "client-2"))
                .thenReturn(Optional.empty());
        when(members.findUserIdsByRoomId(ROOM_ID)).thenReturn(List.of(USER_ID, OTHER_ID));
        when(messages.saveAndFlush(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(MESSAGE_ID);
            }
            return message;
        });
        when(mentions.findAllById_MessageIdIn(any()))
                .thenReturn(List.of(new MessageMention(MESSAGE_ID, OTHER_ID)));

        ChatDtos.MessageView result = service.sendMessage(
                principal(), ROOM_ID, request("Hello **team**", "client-2", List.of(OTHER_ID)));

        assertEquals(MESSAGE_ID, result.id());
        assertEquals("Hello **team**", result.bodyMarkdown());
        assertEquals(List.of(OTHER_ID), result.mentionedUserIds());
        assertEquals(MESSAGE_ID, membership.getLastReadMessageId());
        assertEquals(result.createdAt(), room.getLastMessageAt());
        verify(mentions).saveAll(any());
        verify(events).publishMessageCreated(result, USER_ID);
        verify(events).publishMention(result, OTHER_ID);
    }

    @Test
    void sendMessageRejectsMutedMember() {
        ChatMember membership = membership(ChatMember.Role.MEMBER);
        membership.setMutedUntil(Instant.now().plusSeconds(60));
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room(false)));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(membership));
        when(messages.findByRoomIdAndAuthorIdAndClientMessageId(ROOM_ID, USER_ID, "client-3"))
                .thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(principal(), ROOM_ID, request("hello", "client-3", List.of())));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void sendMessageRejectsOrdinaryMemberInReadOnlyRoom() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room(true)));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(membership(ChatMember.Role.MEMBER)));
        when(messages.findByRoomIdAndAuthorIdAndClientMessageId(ROOM_ID, USER_ID, "client-4"))
                .thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(principal(), ROOM_ID, request("hello", "client-4", List.of())));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(messages, never()).saveAndFlush(any());
    }

    private ChatPrincipal principal() {
        return new ChatPrincipal(USER_ID, Set.of(ChatPrincipal.READ, ChatPrincipal.WRITE), null);
    }

    private ChatRoom room(boolean readOnly) {
        ChatRoom room = new ChatRoom();
        room.setId(ROOM_ID);
        room.setKind(ChatRoom.Kind.GROUP);
        room.setTitle("Team");
        room.setOwnerId(USER_ID);
        room.setReadOnly(readOnly);
        room.setCreatedAt(Instant.parse("2026-10-20T08:00:00Z"));
        room.setLastMessageAt(Instant.parse("2026-10-20T08:00:00Z"));
        return room;
    }

    private ChatMember membership(ChatMember.Role role) {
        ChatMember member = new ChatMember(ROOM_ID, USER_ID, role);
        member.setJoinedAt(Instant.parse("2026-10-20T08:00:00Z"));
        return member;
    }

    private ChatMessage existingMessage() {
        ChatMessage message = new ChatMessage();
        message.setId(MESSAGE_ID);
        message.setRoomId(ROOM_ID);
        message.setAuthorId(USER_ID);
        message.setKind(ChatMessage.Kind.TEXT);
        message.setBodyMarkdown("original");
        message.setClientMessageId("client-1");
        message.setCreatedAt(Instant.parse("2026-10-20T09:00:00Z"));
        return message;
    }

    private ChatDtos.SendMessageRequest request(String body, String clientMessageId, List<UUID> mentioned) {
        return new ChatDtos.SendMessageRequest(
                body, null, clientMessageId, ChatMessage.Kind.TEXT, null, mentioned);
    }
}
