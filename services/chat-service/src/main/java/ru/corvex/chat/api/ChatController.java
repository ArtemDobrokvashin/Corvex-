package ru.corvex.chat.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.chat.client.AuthDirectoryClient;
import ru.corvex.chat.security.ChatPrincipal;
import ru.corvex.chat.service.ChatService;
import ru.corvex.chat.service.PresenceService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API persistent-чата.
 *
 * <p>Gateway маршрутизирует {@code /api/v1/chat/**} без срезания префикса. Controller намеренно тонкий:
 * членство, mute/read-only, треды, идемпотентность и публикация STOMP-событий централизованы
 * в {@link ChatService}, чтобы REST и WebSocket не могли разойтись по правилам доступа.</p>
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chat;
    private final AuthDirectoryClient directory;
    private final PresenceService presence;

    public ChatController(ChatService chat, AuthDirectoryClient directory, PresenceService presence) {
        this.chat = chat;
        this.directory = directory;
        this.presence = presence;
    }

    @GetMapping("/rooms")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    List<ChatDtos.RoomSummaryView> rooms(@AuthenticationPrincipal Jwt jwt) {
        return chat.listRooms(principal(jwt));
    }

    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CHAT_READ')")
    ChatDtos.RoomSummaryView createRoom(@AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody ChatDtos.CreateRoomRequest request) {
        return chat.createRoom(principal(jwt), request);
    }

    @PostMapping("/rooms/direct/{userId}")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    ChatDtos.RoomSummaryView direct(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        return chat.openDirect(principal(jwt), userId);
    }

    @GetMapping("/rooms/{roomId}/members")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    List<ChatDtos.MemberView> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId) {
        return chat.listMembers(principal(jwt), roomId);
    }

    @PostMapping("/rooms/{roomId}/members")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    ChatDtos.RoomSummaryView addMembers(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable UUID roomId,
                                        @Valid @RequestBody ChatDtos.AddMembersRequest request) {
        return chat.addMembers(principal(jwt), roomId, request);
    }

    @DeleteMapping("/rooms/{roomId}/members/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CHAT_READ')")
    void leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId) {
        chat.leaveRoom(principal(jwt), roomId);
    }

    @GetMapping("/rooms/{roomId}/messages")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    ChatDtos.MessagePage history(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable UUID roomId,
                                 @RequestParam(required = false) UUID before,
                                 @RequestParam(required = false) UUID threadRootId,
                                 @RequestParam(defaultValue = "50") int limit) {
        return chat.history(principal(jwt), roomId, before, threadRootId, limit);
    }

    /** REST-отправка — тот же сервисный путь, что STOMP; удобна клиентам и как fallback при обрыве WebSocket. */
    @PostMapping("/rooms/{roomId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CHAT_WRITE')")
    ChatDtos.MessageView send(@AuthenticationPrincipal Jwt jwt,
                              @PathVariable UUID roomId,
                              @Valid @RequestBody ChatDtos.SendMessageRequest request) {
        return chat.sendMessage(principal(jwt), roomId, request);
    }

    @PatchMapping("/messages/{messageId}")
    @PreAuthorize("hasAuthority('CHAT_WRITE')")
    ChatDtos.MessageView edit(@AuthenticationPrincipal Jwt jwt,
                              @PathVariable UUID messageId,
                              @Valid @RequestBody ChatDtos.EditMessageRequest request) {
        return chat.editMessage(principal(jwt), messageId, request);
    }

    @DeleteMapping("/messages/{messageId}")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    ChatDtos.MessageView delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID messageId) {
        return chat.deleteMessage(principal(jwt), messageId);
    }

    @PutMapping("/messages/{messageId}/reactions")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    ChatDtos.MessageView reaction(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable UUID messageId,
                                  @Valid @RequestBody ChatDtos.ReactionRequest request) {
        return chat.toggleReaction(principal(jwt), messageId, request);
    }

    @PutMapping("/messages/{messageId}/pin")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    ChatDtos.MessageView pin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID messageId) {
        return chat.setPinned(principal(jwt), messageId, true);
    }

    @DeleteMapping("/messages/{messageId}/pin")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    ChatDtos.MessageView unpin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID messageId) {
        return chat.setPinned(principal(jwt), messageId, false);
    }

    @PostMapping("/rooms/{roomId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CHAT_READ')")
    void markRead(@AuthenticationPrincipal Jwt jwt,
                  @PathVariable UUID roomId,
                  @Valid @RequestBody ChatDtos.ReadRequest request) {
        chat.markRead(principal(jwt), roomId, request);
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    List<ChatDtos.MessageView> search(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam String q,
                                      @RequestParam(required = false) UUID roomId,
                                      @RequestParam(defaultValue = "50") int limit) {
        return chat.search(principal(jwt), q, roomId, limit);
    }

    @GetMapping("/directory")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    List<ChatDtos.DirectoryUserView> directory(@AuthenticationPrincipal Jwt jwt,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size) {
        // ChatPrincipal валидирует subject/claims даже при прямом вызове AuthDirectoryClient.
        principal(jwt);
        return directory.listActiveUsers(q, page, size).stream()
                .map(user -> new ChatDtos.DirectoryUserView(
                        user.id(),
                        user.username(),
                        user.email(),
                        user.displayName(),
                        user.department(),
                        user.status(),
                        presence.status(user.id()).name()))
                .toList();
    }

    @GetMapping("/presence")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    Map<UUID, String> presence(@AuthenticationPrincipal Jwt jwt,
                               @RequestParam List<UUID> userIds) {
        principal(jwt);
        if (userIds.size() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "За один запрос можно получить статусы не более 500 пользователей");
        }
        return presence.statuses(userIds);
    }

    @PutMapping("/presence")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CHAT_READ')")
    void setPresence(@AuthenticationPrincipal Jwt jwt,
                     @Valid @RequestBody ChatDtos.PresenceRequest request) {
        ChatPrincipal principal = principal(jwt);
        presence.setStatus(principal.userId(), request.status());
    }

    private ChatPrincipal principal(Jwt jwt) {
        return ChatPrincipal.from(jwt);
    }
}
