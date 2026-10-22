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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
        Instant now = Instant.parse("2026-10-20T09:00:00Z");
        return Jwt.withTokenValue("valid-token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim("permissions", List.of(ChatPrincipal.READ, ChatPrincipal.WRITE))
                .build();
    }
}
