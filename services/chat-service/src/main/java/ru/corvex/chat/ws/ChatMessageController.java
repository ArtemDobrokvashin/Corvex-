package ru.corvex.chat.ws;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.chat.api.ChatDtos;
import ru.corvex.chat.security.ChatPrincipal;
import ru.corvex.chat.service.ChatService;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP-эндпоинты чата. Схема адресации:
 * <pre>
 *   клиент -&gt; /app/rooms/{roomId}/send         (persistent send через ChatService)
 *   клиент -&gt; /app/rooms/{roomId}/read         (точка прочтения)
 *   клиент -&gt; /app/rooms/{roomId}/typing       (эфемерное событие)
 *   сервер -&gt; /topic/rooms/{roomId}            (ChatEventView после commit в chat_db)
 *   сервер -&gt; /user/queue/notifications        (упоминания)
 *   сервер -&gt; /user/queue/errors               (ошибки валидации/доступа в handler)
 * </pre>
 * Аутентификация CONNECT и авторизация SUBSCRIBE/SEND выполняются StompAuthChannelInterceptor;
 * сервис всё равно проверяет членство и права повторно как последний рубеж.
 */
@Controller
@Validated
public class ChatMessageController {

    private final ChatService chat;

    public ChatMessageController(ChatService chat) {
        this.chat = chat;
    }

    @MessageMapping("/rooms/{roomId}/send")
    public void send(@DestinationVariable UUID roomId,
                     @Payload @Valid ChatDtos.SendMessageRequest request,
                     Principal principal) {
        chat.sendMessage(principal(principal), roomId, request);
    }

    @MessageMapping("/rooms/{roomId}/read")
    public void read(@DestinationVariable UUID roomId,
                     @Payload @Valid ChatDtos.ReadRequest request,
                     Principal principal) {
        chat.markRead(principal(principal), roomId, request);
    }

    @MessageMapping("/rooms/{roomId}/typing")
    public void typing(@DestinationVariable UUID roomId, Principal principal) {
        chat.typing(principal(principal), roomId);
    }

    @MessageExceptionHandler(ResponseStatusException.class)
    @SendToUser("/queue/errors")
    public Map<String, Object> handleResponseStatus(ResponseStatusException e) {
        return Map.of(
                "status", e.getStatusCode().value(),
                "detail", e.getReason() == null ? e.getStatusCode().toString() : e.getReason());
    }

    @MessageExceptionHandler(IllegalArgumentException.class)
    @SendToUser("/queue/errors")
    public Map<String, Object> handleIllegalArgument(IllegalArgumentException e) {
        return Map.of(
                "status", HttpStatus.BAD_REQUEST.value(),
                "detail", e.getMessage() == null ? "Некорректный запрос" : e.getMessage());
    }

    private ChatPrincipal principal(Principal principal) {
        if (principal instanceof Authentication authentication) {
            return ChatPrincipal.from(authentication);
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "STOMP-сессия не аутентифицирована");
    }
}
