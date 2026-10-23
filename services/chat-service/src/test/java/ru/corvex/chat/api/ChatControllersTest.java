package ru.corvex.chat.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.chat.client.AuthDirectoryClient;
import ru.corvex.chat.domain.ChatMember;
import ru.corvex.chat.domain.ChatMessage;
import ru.corvex.chat.domain.ChatRoom;
import ru.corvex.chat.security.ChatPrincipal;
import ru.corvex.chat.security.InternalTokenGuard;
import ru.corvex.chat.service.ChatService;
import ru.corvex.chat.service.PresenceService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatControllersTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ROOM_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");

    @Mock private ChatService chat;
    @Mock private AuthDirectoryClient directory;
    @Mock private PresenceService presence;
    @Mock private InternalTokenGuard tokens;

    private ChatController controller;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        controller = new ChatController(chat, directory, presence);
        jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .issuedAt(Instant.parse("2026-10-20T09:00:00Z"))
                .expiresAt(Instant.parse("2026-10-20T10:00:00Z"))
                .claim("permissions", List.of(ChatPrincipal.READ, ChatPrincipal.WRITE))
                .build();
    }

    @Test
    void delegatesRoomOperationsWithPrincipalFromJwt() {
        ChatDtos.RoomSummaryView room = room();
        ChatDtos.CreateRoomRequest create = new ChatDtos.CreateRoomRequest(
                ChatRoom.Kind.GROUP, "Team", List.of(OTHER_ID), false);
        ChatDtos.AddMembersRequest add = new ChatDtos.AddMembersRequest(List.of(OTHER_ID));
        ChatDtos.MemberView member = new ChatDtos.MemberView(
                USER_ID, ChatMember.Role.MEMBER, null, null, Instant.now());
        when(chat.listRooms(any(ChatPrincipal.class))).thenReturn(List.of(room));
        when(chat.createRoom(any(ChatPrincipal.class), eq(create))).thenReturn(room);
        when(chat.openDirect(any(ChatPrincipal.class), eq(OTHER_ID))).thenReturn(room);
        when(chat.listMembers(any(ChatPrincipal.class), eq(ROOM_ID))).thenReturn(List.of(member));
        when(chat.addMembers(any(ChatPrincipal.class), eq(ROOM_ID), eq(add))).thenReturn(room);

        assertEquals(List.of(room), controller.rooms(jwt));
        assertEquals(room, controller.createRoom(jwt, create));
        assertEquals(room, controller.direct(jwt, OTHER_ID));
        assertEquals(List.of(member), controller.members(jwt, ROOM_ID));
        assertEquals(room, controller.addMembers(jwt, ROOM_ID, add));
        controller.leave(jwt, ROOM_ID);

        ArgumentCaptor<ChatPrincipal> principal = ArgumentCaptor.forClass(ChatPrincipal.class);
        verify(chat).listRooms(principal.capture());
        assertEquals(USER_ID, principal.getValue().userId());
        assertEquals(jwt, principal.getValue().jwt());
        verify(chat).leaveRoom(any(ChatPrincipal.class), eq(ROOM_ID));
    }

    @Test
    void delegatesMessageOperations() {
        ChatDtos.MessageView message = message();
        ChatDtos.MessagePage page = new ChatDtos.MessagePage(List.of(message), false, null);
        ChatDtos.SendMessageRequest send = new ChatDtos.SendMessageRequest(
                "hello", null, "client", ChatMessage.Kind.TEXT, null, List.of());
        ChatDtos.EditMessageRequest edit = new ChatDtos.EditMessageRequest("updated");
        ChatDtos.ReactionRequest reaction = new ChatDtos.ReactionRequest("👍");
        ChatDtos.ReadRequest read = new ChatDtos.ReadRequest(MESSAGE_ID);
        when(chat.history(any(), eq(ROOM_ID), eq(MESSAGE_ID), eq(OTHER_ID), eq(25))).thenReturn(page);
        when(chat.sendMessage(any(), eq(ROOM_ID), eq(send))).thenReturn(message);
        when(chat.editMessage(any(), eq(MESSAGE_ID), eq(edit))).thenReturn(message);
        when(chat.deleteMessage(any(), eq(MESSAGE_ID))).thenReturn(message);
        when(chat.toggleReaction(any(), eq(MESSAGE_ID), eq(reaction))).thenReturn(message);
        when(chat.setPinned(any(), eq(MESSAGE_ID), eq(true))).thenReturn(message);
        when(chat.setPinned(any(), eq(MESSAGE_ID), eq(false))).thenReturn(message);
        when(chat.search(any(), eq("hello"), eq(ROOM_ID), eq(10))).thenReturn(List.of(message));

        assertEquals(page, controller.history(jwt, ROOM_ID, MESSAGE_ID, OTHER_ID, 25));
        assertEquals(message, controller.send(jwt, ROOM_ID, send));
        assertEquals(message, controller.edit(jwt, MESSAGE_ID, edit));
        assertEquals(message, controller.delete(jwt, MESSAGE_ID));
        assertEquals(message, controller.reaction(jwt, MESSAGE_ID, reaction));
        assertEquals(message, controller.pin(jwt, MESSAGE_ID));
        assertEquals(message, controller.unpin(jwt, MESSAGE_ID));
        controller.markRead(jwt, ROOM_ID, read);
        assertEquals(List.of(message), controller.search(jwt, "hello", ROOM_ID, 10));
        verify(chat).markRead(any(ChatPrincipal.class), eq(ROOM_ID), eq(read));
    }

    @Test
    void directoryMapsRemoteUsersAndPresence() {
        AuthDirectoryClient.RemoteUser remote = new AuthDirectoryClient.RemoteUser(
                OTHER_ID, "petr", "petr@example.test", "Petr Petrov", "LOCAL", "ACTIVE",
                "Sales", List.of("EMPLOYEES"), List.of("CHAT_READ"), 100L, 1L,
                false, false, null, Instant.parse("2026-10-01T00:00:00Z"));
        when(directory.listActiveUsers("petr", 2, 20)).thenReturn(List.of(remote));
        when(presence.status(OTHER_ID)).thenReturn(PresenceService.Status.DND);

        List<ChatDtos.DirectoryUserView> result = controller.directory(jwt, "petr", 2, 20);

        assertEquals(1, result.size());
        assertEquals(OTHER_ID, result.get(0).id());
        assertEquals("Petr Petrov", result.get(0).displayName());
        assertEquals("DND", result.get(0).presence());
    }

    @Test
    void presenceEndpointsValidateBatchSizeAndDelegateStatus() {
        when(presence.statuses(List.of(USER_ID, OTHER_ID)))
                .thenReturn(Map.of(USER_ID, "ONLINE", OTHER_ID, "OFFLINE"));
        assertEquals(Map.of(USER_ID, "ONLINE", OTHER_ID, "OFFLINE"),
                controller.presence(jwt, List.of(USER_ID, OTHER_ID)));

        List<UUID> tooMany = Stream.generate(UUID::randomUUID).limit(501).toList();
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.presence(jwt, tooMany)).getStatusCode());

        controller.setPresence(jwt, new ChatDtos.PresenceRequest("AWAY"));
        verify(presence).setStatus(USER_ID, "AWAY");
    }

    @Test
    void invalidJwtFailsBeforeDirectoryCall() {
        Jwt noSubject = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("scope", "chat")
                .build();

        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.directory(noSubject, null, 0, 50)).getStatusCode());
        verifyNoInteractions(directory);
    }

    @Test
    void internalMembershipChecksTokenBeforeDelegating() {
        InternalChatController internal = new InternalChatController(chat, tokens);
        ChatDtos.MembershipInternalView expected = new ChatDtos.MembershipInternalView(
                ROOM_ID, USER_ID, true, ChatMember.Role.OWNER, null, false);
        when(chat.internalMembership(ROOM_ID, USER_ID)).thenReturn(expected);

        assertEquals(expected, internal.membership("token", ROOM_ID, USER_ID));
        verify(tokens).verify("token");
    }

    @Test
    void internalMembershipRejectsWrongTokenWithoutReadingChat() {
        InternalChatController internal = new InternalChatController(
                chat, new InternalTokenGuard("expected"));

        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> internal.membership("wrong", ROOM_ID, USER_ID)).getStatusCode());
        verifyNoInteractions(chat);
    }

    private ChatDtos.RoomSummaryView room() {
        return new ChatDtos.RoomSummaryView(
                ROOM_ID, ChatRoom.Kind.GROUP, "Team", USER_ID, false,
                List.of(USER_ID), ChatMember.Role.OWNER, 1, 0, 0, null,
                Instant.now(), Instant.now());
    }

    private ChatDtos.MessageView message() {
        return new ChatDtos.MessageView(
                MESSAGE_ID, ROOM_ID, USER_ID, ChatMessage.Kind.TEXT, "hello", null, null,
                false, null, false, "client", List.of(), List.of(), Instant.now());
    }
}
