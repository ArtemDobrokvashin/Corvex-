package ru.corvex.chat.ws;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.chat.security.ChatPrincipal;
import ru.corvex.chat.service.ChatService;

import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Аутентификация и авторизация STOMP на inbound-канале.
 *
 * <p>HTTP handshake SockJS открыт намеренно: токен нельзя безопасно передать стандартным
 * WebSocket API как Authorization header, поэтому Bearer JWT идёт в STOMP CONNECT.
 * После CONNECT каждая подписка на комнату и каждая отправка повторно проверяются
 * по актуальному членству в PostgreSQL, mute и read-only состоянию комнаты.</p>
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Pattern ROOM_TOPIC = Pattern.compile(
            "^/topic/rooms/([0-9a-fA-F-]{36})(?:/typing)?$");
    private static final Pattern ROOM_SEND = Pattern.compile(
            "^/app/rooms/([0-9a-fA-F-]{36})/(send|typing|read)$");

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtConverter;
    private final ObjectProvider<ChatService> chatService;

    public StompAuthChannelInterceptor(JwtDecoder jwtDecoder,
                                       JwtAuthenticationConverter jwtConverter,
                                       ObjectProvider<ChatService> chatService) {
        this.jwtDecoder = jwtDecoder;
        this.jwtConverter = jwtConverter;
        this.chatService = chatService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            authenticate(message, accessor);
            return message;
        }
        if (command == StompCommand.DISCONNECT) {
            return message;
        }

        ChatPrincipal principal = currentPrincipal(message, accessor);
        String destination = accessor.getDestination();
        if (command == StompCommand.SUBSCRIBE) {
            authorizeSubscribe(message, principal, destination);
        } else if (command == StompCommand.SEND) {
            authorizeSend(message, principal, destination);
        }
        return message;
    }

    private void authenticate(Message<?> message, StompHeaderAccessor accessor) {
        String authorization = firstNativeHeader(accessor, "Authorization");
        if (authorization == null) {
            authorization = firstNativeHeader(accessor, "authorization");
        }
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw denied(message, "Для STOMP CONNECT нужен Bearer JWT");
        }

        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            throw denied(message, "Пустой Bearer JWT");
        }

        try {
            Jwt jwt = jwtDecoder.decode(token);
            Authentication authentication = jwtConverter.convert(jwt);
            if (authentication == null) {
                throw denied(message, "Не удалось создать аутентификацию из JWT");
            }
            ChatPrincipal principal = ChatPrincipal.from(authentication);
            principal.require(ChatPrincipal.READ);
            accessor.setUser(authentication);
            if (accessor.getSessionAttributes() != null) {
                accessor.getSessionAttributes().put("chatPrincipal", principal);
                accessor.getSessionAttributes().put("userId", principal.userId());
            }
        } catch (MessageDeliveryException e) {
            throw e;
        } catch (JwtException | ResponseStatusException | IllegalArgumentException e) {
            throw denied(message, "Недействительный JWT: " + safeReason(e));
        }
    }

    private void authorizeSubscribe(Message<?> message, ChatPrincipal principal, String destination) {
        if (destination == null || destination.isBlank()) {
            throw denied(message, "Не указана тема подписки");
        }
        if ("/user/queue/notifications".equals(destination)
                || "/user/queue/errors".equals(destination)) {
            return;
        }
        Matcher room = ROOM_TOPIC.matcher(destination);
        if (room.matches()) {
            requireRoomAccess(message, principal, room.group(1), false);
            return;
        }
        throw denied(message, "Подписка на эту тему запрещена");
    }

    private void authorizeSend(Message<?> message, ChatPrincipal principal, String destination) {
        if (destination == null || destination.isBlank()) {
            throw denied(message, "Не указан адрес отправки");
        }
        Matcher room = ROOM_SEND.matcher(destination);
        if (!room.matches()) {
            throw denied(message, "Отправка в этот адрес запрещена");
        }
        boolean write = !"read".equals(room.group(2));
        requireRoomAccess(message, principal, room.group(1), write);
    }

    private void requireRoomAccess(Message<?> message, ChatPrincipal principal,
                                   String rawRoomId, boolean write) {
        try {
            UUID roomId = UUID.fromString(rawRoomId.toLowerCase(Locale.ROOT));
            chatService.getObject().requireRealtimeAccess(principal, roomId, write);
        } catch (IllegalArgumentException e) {
            throw denied(message, "Некорректный идентификатор комнаты");
        } catch (ResponseStatusException e) {
            throw denied(message, safeReason(e));
        }
    }

    private ChatPrincipal currentPrincipal(Message<?> message, StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof Authentication authentication) {
            try {
                return ChatPrincipal.from(authentication);
            } catch (ResponseStatusException e) {
                throw denied(message, safeReason(e));
            }
        }
        if (accessor.getSessionAttributes() != null) {
            Object saved = accessor.getSessionAttributes().get("chatPrincipal");
            if (saved instanceof ChatPrincipal principal) {
                return principal;
            }
        }
        throw denied(message, "STOMP-сессия не аутентифицирована");
    }

    private String firstNativeHeader(StompHeaderAccessor accessor, String name) {
        List<String> values = accessor.getNativeHeader(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private MessageDeliveryException denied(Message<?> message, String detail) {
        return new MessageDeliveryException(message, detail);
    }

    private String safeReason(Exception e) {
        if (e instanceof ResponseStatusException response) {
            return response.getReason() == null ? response.getStatusCode().toString() : response.getReason();
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
