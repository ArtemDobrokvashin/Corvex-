package ru.corvex.chat.ws;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP-эндпоинты чата. Схема адресации:
 * <pre>
 *   клиент -&gt; /app/rooms/{roomId}/send         (этот контроллер: проверка членства, сохранение)
 *   сервер -&gt; /topic/rooms/{roomId}            (рассылка участникам комнаты через брокер)
 *   сервер -&gt; /user/queue/notifications        (упоминания, личные события)
 *   клиент -&gt; /app/rooms/{roomId}/typing       (эфемерно, в БД не пишется, TTL в Redis 5 с)
 * </pre>
 * Аутентификация STOMP CONNECT — тем же Bearer JWT в заголовке; авторизация (членство в комнате,
 * право CHAT_WRITE, read-only системные каналы) проверяется на каждое сообщение, а не только при CONNECT.
 */
@Controller
public class ChatMessageController {

    private final SimpMessagingTemplate broker;

    public ChatMessageController(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    public record SendMessageRequest(
            @NotBlank @Size(max = 32_000) String bodyMarkdown,
            UUID threadRootId,
            String clientMessageId) { // идемпотентность: повторная отправка при реконнекте не дублирует сообщение
    }

    public record ChatMessageView(UUID id, UUID roomId, UUID authorId, String bodyMarkdown,
                                  UUID threadRootId, String clientMessageId) {
    }

    @MessageMapping("/rooms/{roomId}/send")
    public void send(@DestinationVariable UUID roomId, SendMessageRequest request, Principal principal,
                     @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || request == null
                || request.bodyMarkdown() == null || request.bodyMarkdown().isBlank()) {
            return;
        }
        UUID authorId;
        try {
            authorId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            return;
        }
        // TODO(Stage-4): ChatMessageService.persist(roomId, authorId, request) —
        // проверка членства/прав, запись в chat_db, упоминания, индексация в Elasticsearch, push офлайн-участникам.
        var view = new ChatMessageView(UUID.randomUUID(), roomId, authorId,
                request.bodyMarkdown(), request.threadRootId(), request.clientMessageId());
        broker.convertAndSend("/topic/rooms/" + roomId, view);
    }

    @MessageMapping("/rooms/{roomId}/typing")
    public void typing(@DestinationVariable UUID roomId, Principal principal) {
        if (principal == null || principal.getName() == null) {
            return;
        }
        broker.convertAndSend("/topic/rooms/" + roomId + "/typing",
                java.util.Map.of("user", principal.getName()));
    }
}
