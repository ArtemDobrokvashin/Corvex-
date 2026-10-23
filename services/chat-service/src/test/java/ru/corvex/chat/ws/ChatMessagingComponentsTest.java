package ru.corvex.chat.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import ru.corvex.chat.api.ChatDtos;
import ru.corvex.chat.domain.ChatMessage;
import ru.corvex.chat.security.ChatPrincipal;
import ru.corvex.chat.service.ChatService;
import ru.corvex.chat.service.PresenceService;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ChatMessagingComponentsTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ROOM_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");

    @Mock private ChatService chat;
    @Mock private PresenceService presence;

    @Test
    void stompControllerDelegatesSendReadAndTyping() {
        ChatMessageController controller = new ChatMessageController(chat);
        ChatDtos.SendMessageRequest send = new ChatDtos.SendMessageRequest(
                "hello", null, "client", ChatMessage.Kind.TEXT, null, List.of());
        ChatDtos.ReadRequest read = new ChatDtos.ReadRequest(MESSAGE_ID);

        controller.send(ROOM_ID, send, authentication());
        controller.read(ROOM_ID, read, authentication());
        controller.typing(ROOM_ID, authentication());

        verify(chat).sendMessage(any(ChatPrincipal.class), eq(ROOM_ID), eq(send));
        verify(chat).markRead(any(ChatPrincipal.class), eq(ROOM_ID), eq(read));
        verify(chat).typing(any(ChatPrincipal.class), eq(ROOM_ID));
    }

    @Test
    void stompControllerRejectsNonAuthenticationPrincipal() {
        ChatMessageController controller = new ChatMessageController(chat);
        Principal plain = org.mockito.Mockito.mock(Principal.class);

        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.typing(ROOM_ID, plain)).getStatusCode());
        verifyNoInteractions(chat);
    }

    @Test
    void stompExceptionHandlersBuildUserErrorPayloads() {
        ChatMessageController controller = new ChatMessageController(chat);

        Map<String, Object> forbidden = controller.handleResponseStatus(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "not a member"));
        assertEquals(Map.of("status", 403, "detail", "not a member"), forbidden);

        Map<String, Object> noReason = controller.handleResponseStatus(
                new ResponseStatusException(HttpStatus.CONFLICT));
        assertEquals(409, noReason.get("status"));
        assertEquals("409 CONFLICT", noReason.get("detail"));

        assertEquals(Map.of("status", 400, "detail", "bad input"),
                controller.handleIllegalArgument(new IllegalArgumentException("bad input")));
        assertEquals(Map.of("status", 400, "detail", "Некорректный запрос"),
                controller.handleIllegalArgument(new IllegalArgumentException()));
    }

    @Test
    void presenceListenerConnectsHeartbeatsAndDisconnectsAuthenticatedSessions() {
        PresenceEventListener listener = new PresenceEventListener(presence);
        Authentication authentication = authentication();

        listener.onConnected(new SessionConnectedEvent(
                this, stompMessage(StompCommand.CONNECT, "session-1"), authentication));
        listener.onSubscribe(new SessionSubscribeEvent(
                this, stompMessage(StompCommand.SUBSCRIBE, "session-1"), authentication));
        listener.onDisconnect(new SessionDisconnectEvent(
                this, stompMessage(StompCommand.DISCONNECT, "ignored"),
                "session-1", CloseStatus.NORMAL, authentication));

        verify(presence).onConnect(USER_ID, "session-1");
        verify(presence).heartbeat(USER_ID, "session-1");
        verify(presence).onDisconnect(USER_ID, "session-1");
    }

    @Test
    void presenceListenerIgnoresMissingPlainAndBrokenPrincipals() {
        PresenceEventListener listener = new PresenceEventListener(presence);
        Principal plain = org.mockito.Mockito.mock(Principal.class);
        Authentication broken = new UsernamePasswordAuthenticationToken(
                "not-a-uuid", null,
                List.of(new SimpleGrantedAuthority(ChatPrincipal.READ)));

        listener.onConnected(new SessionConnectedEvent(
                this, stompMessage(StompCommand.CONNECT, "s1")));
        listener.onSubscribe(new SessionSubscribeEvent(
                this, stompMessage(StompCommand.SUBSCRIBE, "s2"), plain));
        listener.onDisconnect(new SessionDisconnectEvent(
                this, stompMessage(StompCommand.DISCONNECT, "s3"),
                "s3", CloseStatus.NORMAL, broken));

        verifyNoInteractions(presence);
    }

    private Authentication authentication() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .issuedAt(Instant.parse("2026-10-20T09:00:00Z"))
                .expiresAt(Instant.parse("2026-10-20T10:00:00Z"))
                .claim("permissions", List.of(ChatPrincipal.READ, ChatPrincipal.WRITE))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(
                new SimpleGrantedAuthority(ChatPrincipal.READ),
                new SimpleGrantedAuthority(ChatPrincipal.WRITE)));
    }

    private Message<byte[]> stompMessage(StompCommand command, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
