package ru.corvex.chat.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.chat.security.InternalTokenGuard;
import ru.corvex.chat.service.ChatService;

import java.util.UUID;

/**
 * Internal API только для сервисного взаимодействия внутри Compose-сети.
 * Gateway не маршрутизирует {@code /internal/**}; дополнительно каждый запрос проходит
 * fail-closed проверку X-Corvex-Internal-Token.
 */
@RestController
@RequestMapping("/internal/chat")
public class InternalChatController {

    private final ChatService chat;
    private final InternalTokenGuard tokens;

    public InternalChatController(ChatService chat, InternalTokenGuard tokens) {
        this.chat = chat;
        this.tokens = tokens;
    }

    @GetMapping("/rooms/{roomId}/members/{userId}")
    ChatDtos.MembershipInternalView membership(
            @RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
            @PathVariable UUID roomId,
            @PathVariable UUID userId) {
        tokens.verify(token);
        return chat.internalMembership(roomId, userId);
    }
}
