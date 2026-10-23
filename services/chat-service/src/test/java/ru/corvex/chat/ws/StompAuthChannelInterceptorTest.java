package ru.corvex.chat.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.chat.security.ChatPrincipal;
import ru.corvex.chat.service.ChatService;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StompAuthChannelInterceptorTest {

    private static final UUID USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private JwtDecoder decoder;
    private JwtAuthenticationConverter converter;
    private ChatService chatService;
    private StompAuthChannelInterceptor interceptor;
    private MessageChannel channel;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        decoder = mock(JwtDecoder.class);
        converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> permissions = jwt.getClaimAsStringList("permissions");
            if (permissions == null) {
                return List.of();
            }
            return permissions.stream()
                    .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p))
                    .toList();
        });
        chatService = mock(ChatService.class);
        ObjectProvider<ChatService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(chatService);
        interceptor = new StompAuthChannelInterceptor(decoder, converter, provider);
        channel = mock(MessageChannel.class);
        when(decoder.decode("valid-token")).thenReturn(jwt());
    }

    @Test
    void connectWithBearerJwtSetsUserAndSessionPrincipal() {
        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer valid-token");

        interceptor.preSend(message(accessor), channel);

        assertEquals(USER_ID.toString(), accessor.getUser().getName());
        assertEquals(USER_ID, accessor.getSessionAttributes().get("userId"));
    }

    @Test
    void connectWithoutBearerJwtIsRejected() {
        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);

        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message(accessor), channel));
    }

    @Test
    void sendToRoomChecksWriteMembershipAndPropagatesForbiddenAsDeliveryError() {
        StompHeaderAccessor accessor = accessor(StompCommand.SEND);
        accessor.setDestination("/app/rooms/" + ROOM_ID + "/send");
        accessor.setUser(authentication());
        when(chatService.requireRealtimeAccess(any(ChatPrincipal.class), eq(ROOM_ID), eq(true)))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "not a member"));

        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message(accessor), channel));
        verify(chatService).requireRealtimeAccess(any(ChatPrincipal.class), eq(ROOM_ID), eq(true));
    }

    @Test
    void subscribeToOwnNotificationsDoesNotRequireRoomMembership() {
        StompHeaderAccessor accessor = accessor(StompCommand.SUBSCRIBE);
        accessor.setDestination("/user/queue/notifications");
        accessor.setUser(authentication());

        interceptor.preSend(message(accessor), channel);

        verify(chatService, never()).requireRealtimeAccess(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void nonStompAndCommandlessMessagesPassThrough() {
        Message<?> plain = MessageBuilder.withPayload("hello").build();
        assertEquals(plain, interceptor.preSend(plain, channel));

        StompHeaderAccessor noCommand = StompHeaderAccessor.createForHeartbeat();
        Message<?> commandless = MessageBuilder.createMessage(
                new byte[0], noCommand.getMessageHeaders());
        assertEquals(commandless, interceptor.preSend(commandless, channel));
    }

    @Test
    void disconnectAndOtherAuthenticatedCommandsPassThrough() {
        StompHeaderAccessor disconnect = accessor(StompCommand.DISCONNECT);
        Message<?> disconnectMessage = message(disconnect);
        assertEquals(disconnectMessage, interceptor.preSend(disconnectMessage, channel));

        StompHeaderAccessor messageCommand = accessor(StompCommand.MESSAGE);
        messageCommand.setUser(authentication());
        Message<?> outbound = message(messageCommand);
        assertEquals(outbound, interceptor.preSend(outbound, channel));
    }

    @Test
    void connectAcceptsCaseInsensitiveHeaderAndBearerScheme() {
        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);
        accessor.setNativeHeader("authorization", "bearer valid-token");

        interceptor.preSend(message(accessor), channel);

        assertEquals(USER_ID, accessor.getSessionAttributes().get("userId"));
    }

    @Test
    void connectRejectsBlankInvalidAndUnreadableTokens() {
        StompHeaderAccessor blank = accessor(StompCommand.CONNECT);
        blank.setNativeHeader("Authorization", "Bearer    ");
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message(blank), channel));

        when(decoder.decode("bad-token")).thenThrow(new JwtException("expired"));
        StompHeaderAccessor invalid = accessor(StompCommand.CONNECT);
        invalid.setNativeHeader("Authorization", "Bearer bad-token");
        MessageDeliveryException error = assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message(invalid), channel));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("expired"));

        when(decoder.decode("write-only")).thenReturn(jwtWithPermissions(List.of(ChatPrincipal.WRITE)));
        StompHeaderAccessor noRead = accessor(StompCommand.CONNECT);
        noRead.setNativeHeader("Authorization", "Bearer write-only");
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message(noRead), channel));
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectRejectsNullConvertedAuthentication() {
        JwtDecoder localDecoder = mock(JwtDecoder.class);
        JwtAuthenticationConverter localConverter = mock(JwtAuthenticationConverter.class);
        ObjectProvider<ChatService> provider = mock(ObjectProvider.class);
        when(localDecoder.decode("valid-token")).thenReturn(jwt());
        when(localConverter.convert(any(Jwt.class))).thenReturn(null);
        StompAuthChannelInterceptor local =
                new StompAuthChannelInterceptor(localDecoder, localConverter, provider);
        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer valid-token");

        assertThrows(MessageDeliveryException.class,
                () -> local.preSend(message(accessor), channel));
    }

    @Test
    void subscribeChecksRoomTopicsAndRejectsUnknownTopics() {
        StompHeaderAccessor room = accessor(StompCommand.SUBSCRIBE);
        room.setDestination("/topic/rooms/" + ROOM_ID + "/typing");
        room.setUser(authentication());
        interceptor.preSend(message(room), channel);
        verify(chatService).requireRealtimeAccess(any(ChatPrincipal.class), eq(ROOM_ID), eq(false));

        StompHeaderAccessor errors = accessor(StompCommand.SUBSCRIBE);
        errors.setDestination("/user/queue/errors");
        errors.setUser(authentication());
        interceptor.preSend(message(errors), channel);

        StompHeaderAccessor unknown = accessor(StompCommand.SUBSCRIBE);
        unknown.setDestination("/topic/admin");
        unknown.setUser(authentication());
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message(unknown), channel));

        StompHeaderAccessor blank = accessor(StompCommand.SUBSCRIBE);
        blank.setUser(authentication());
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message(blank), channel));
    }

    @Test
    void sendChecksReadAsReadAndTypingAsWrite() {
        StompHeaderAccessor read = accessor(StompCommand.SEND);
        read.setDestination("/app/rooms/" + ROOM_ID + "/read");
        read.setUser(authentication());
        interceptor.preSend(message(read), channel);

        StompHeaderAccessor typing = accessor(StompCommand.SEND);
        typing.setDestination("/app/rooms/" + ROOM_ID + "/typing");
        typing.setUser(authentication());
        interceptor.preSend(message(typing), channel);

        verify(chatService).requireRealtimeAccess(any(ChatPrincipal.class), eq(ROOM_ID), eq(false));
        verify(chatService).requireRealtimeAccess(any(ChatPrincipal.class), eq(ROOM_ID), eq(true));

        StompHeaderAccessor unknown = accessor(StompCommand.SEND);
        unknown.setDestination("/app/admin");
        unknown.setUser(authentication());
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message(unknown), channel));
    }

    @Test
    void savedSessionPrincipalIsFallbackAndBrokenAuthenticationIsRejected() {
        ChatPrincipal saved = new ChatPrincipal(
                USER_ID, java.util.Set.of(ChatPrincipal.READ), null);
        StompHeaderAccessor fromSession = accessor(StompCommand.SUBSCRIBE);
        fromSession.setDestination("/user/queue/notifications");
        fromSession.getSessionAttributes().put("chatPrincipal", saved);
        interceptor.preSend(message(fromSession), channel);

        StompHeaderAccessor broken = accessor(StompCommand.SUBSCRIBE);
        broken.setDestination("/user/queue/notifications");
        broken.setUser(new UsernamePasswordAuthenticationToken(
                "not-a-uuid", null, List.of(new SimpleGrantedAuthority(ChatPrincipal.READ))));
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message(broken), channel));

        StompHeaderAccessor anonymous = accessor(StompCommand.SUBSCRIBE);
        anonymous.setDestination("/user/queue/notifications");
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message(anonymous), channel));
    }

    @Test
    void malformedUuidInMatchingDestinationIsRejected() {
        StompHeaderAccessor accessor = accessor(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/rooms/------------------------------------");
        accessor.setUser(authentication());

        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message(accessor), channel));
    }

    private StompHeaderAccessor accessor(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("session-1");
        accessor.setSessionAttributes(new HashMap<>());
        return accessor;
    }

    private Message<?> message(StompHeaderAccessor accessor) {
        // StompDecoder оставляет заголовки mutable для inbound-интерцепторов (setUser при CONNECT).
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Authentication authentication() {
        return converter.convert(jwt());
    }

    private Jwt jwt() {
        return jwtWithPermissions(List.of(ChatPrincipal.READ, ChatPrincipal.WRITE));
    }

    private Jwt jwtWithPermissions(List<String> permissions) {
        Instant now = Instant.parse("2026-10-20T09:00:00Z");
        return Jwt.withTokenValue("valid-token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim("permissions", permissions)
                .build();
    }
}
