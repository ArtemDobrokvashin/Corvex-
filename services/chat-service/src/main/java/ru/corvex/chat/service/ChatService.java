package ru.corvex.chat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.chat.api.ChatDtos;
import ru.corvex.chat.client.AuthDirectoryClient;
import ru.corvex.chat.domain.ChatMember;
import ru.corvex.chat.domain.ChatMessage;
import ru.corvex.chat.domain.ChatRoom;
import ru.corvex.chat.domain.MessageEditHistory;
import ru.corvex.chat.domain.MessageMention;
import ru.corvex.chat.domain.MessageReaction;
import ru.corvex.chat.repository.ChatMemberRepository;
import ru.corvex.chat.repository.ChatMessageRepository;
import ru.corvex.chat.repository.ChatRoomRepository;
import ru.corvex.chat.repository.MessageEditHistoryRepository;
import ru.corvex.chat.repository.MessageMentionRepository;
import ru.corvex.chat.repository.MessageReactionRepository;
import ru.corvex.chat.security.ChatPrincipal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Доменная логика persistent-чата этапа 4.
 *
 * <p>Каждая операция заново проверяет членство и права из JWT: подписка на STOMP-топик или ранее
 * полученный список комнат не считается доказательством доступа. Содержимое удалённого сообщения
 * маскируется уже на уровне DTO, хотя строка остаётся в БД для аудита и истории редактирований.</p>
 */
@Service
@Transactional
public class ChatService {

    private static final int MAX_BODY_LENGTH = 32_000;
    private static final int MAX_ROOM_MEMBERS = 1_000;
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private final ChatRoomRepository rooms;
    private final ChatMemberRepository members;
    private final ChatMessageRepository messages;
    private final MessageEditHistoryRepository editHistory;
    private final MessageReactionRepository reactions;
    private final MessageMentionRepository mentions;
    private final AuthDirectoryClient directory;
    private final ChatEventPublisher events;

    @Value("${corvex.chat.general-room-title:Общий}")
    private String generalRoomTitle;

    public ChatService(ChatRoomRepository rooms,
                       ChatMemberRepository members,
                       ChatMessageRepository messages,
                       MessageEditHistoryRepository editHistory,
                       MessageReactionRepository reactions,
                       MessageMentionRepository mentions,
                       AuthDirectoryClient directory,
                       ChatEventPublisher events) {
        this.rooms = rooms;
        this.members = members;
        this.messages = messages;
        this.editHistory = editHistory;
        this.reactions = reactions;
        this.mentions = mentions;
        this.directory = directory;
        this.events = events;
    }

    // ---------------------------------------------------------------------------------------------
    // Комнаты
    // ---------------------------------------------------------------------------------------------

    public List<ChatDtos.RoomSummaryView> listRooms(ChatPrincipal principal) {
        principal.require(ChatPrincipal.READ);
        ensureGeneralRoom(principal.userId());

        List<ChatMember> myMemberships = members.findAllById_UserId(principal.userId());
        if (myMemberships.isEmpty()) {
            return List.of();
        }

        List<UUID> roomIds = myMemberships.stream().map(ChatMember::getRoomId).distinct().toList();
        Map<UUID, ChatRoom> roomsById = rooms.findAllById(roomIds).stream()
                .collect(Collectors.toMap(ChatRoom::getId, room -> room));
        Map<UUID, List<ChatMember>> membersByRoom = members.findAllById_RoomIdIn(roomIds).stream()
                .collect(Collectors.groupingBy(ChatMember::getRoomId));
        Map<UUID, ChatMember> myMemberByRoom = myMemberships.stream()
                .collect(Collectors.toMap(ChatMember::getRoomId, member -> member));

        Map<UUID, ChatMessage> lastMessageByRoom = new HashMap<>();
        for (UUID roomId : roomIds) {
            messages.findFirstByRoomIdOrderByCreatedAtDescIdDesc(roomId)
                    .ifPresent(message -> lastMessageByRoom.put(roomId, message));
        }
        Map<UUID, ChatDtos.MessageView> lastViewsByMessage = toMessageViews(
                new ArrayList<>(lastMessageByRoom.values())).stream()
                .collect(Collectors.toMap(ChatDtos.MessageView::id, view -> view));

        return roomIds.stream()
                .map(roomsById::get)
                .filter(room -> room != null)
                .sorted(Comparator
                        .comparing((ChatRoom room) -> roomActivity(room), Comparator.reverseOrder())
                        .thenComparing(ChatRoom::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(room -> toRoomSummary(
                        room,
                        myMemberByRoom.get(room.getId()),
                        membersByRoom.getOrDefault(room.getId(), List.of()),
                        Optional.ofNullable(lastMessageByRoom.get(room.getId()))
                                .map(message -> lastViewsByMessage.get(message.getId()))
                                .orElse(null)))
                .toList();
    }

    public ChatDtos.RoomSummaryView createRoom(ChatPrincipal principal,
                                               ChatDtos.CreateRoomRequest request) {
        principal.require(ChatPrincipal.READ);
        if (request == null || request.kind() == null) {
            throw badRequest("Не указан тип комнаты");
        }

        ChatRoom.Kind kind = request.kind();
        switch (kind) {
            case GROUP -> principal.require(ChatPrincipal.CREATE_GROUP);
            case CHANNEL_PUBLIC, CHANNEL_PRIVATE -> principal.require(ChatPrincipal.CREATE_CHANNEL);
            case SYSTEM -> principal.require(ChatPrincipal.MODERATE);
            case DIRECT -> throw badRequest("Личный диалог создаётся через direct endpoint");
        }

        String title = normalizeOptionalTitle(request.title());
        if (kind != ChatRoom.Kind.GROUP && title == null) {
            throw badRequest("Для канала нужно название");
        }
        if (title == null) {
            title = "Групповой чат";
        }

        List<UUID> memberIds = normalizeIds(request.memberIds(), "memberIds").stream()
                .filter(id -> !id.equals(principal.userId()))
                .toList();
        if (memberIds.size() + 1 > MAX_ROOM_MEMBERS) {
            throw unprocessable("В комнате не может быть больше " + MAX_ROOM_MEMBERS + " участников");
        }
        memberIds.forEach(this::requireActiveUser);

        ChatRoom room = new ChatRoom();
        room.setKind(kind);
        room.setTitle(title);
        room.setOwnerId(principal.userId());
        room.setReadOnly(request.readOnly() != null
                ? request.readOnly()
                : kind == ChatRoom.Kind.SYSTEM);
        room = rooms.save(room);

        List<ChatMember> roomMembers = new ArrayList<>();
        roomMembers.add(new ChatMember(room.getId(), principal.userId(), ChatMember.Role.OWNER));
        memberIds.forEach(id -> roomMembers.add(
                new ChatMember(room.getId(), id, ChatMember.Role.MEMBER)));
        roomMembers = members.saveAll(roomMembers);

        ChatMember mine = roomMembers.stream()
                .filter(member -> principal.userId().equals(member.getUserId()))
                .findFirst()
                .orElseThrow();
        return toRoomSummary(room, mine, roomMembers, null);
    }

    public ChatDtos.RoomSummaryView openDirect(ChatPrincipal principal, UUID otherUserId) {
        principal.require(ChatPrincipal.READ);
        if (otherUserId == null) {
            throw badRequest("Не указан собеседник");
        }
        if (otherUserId.equals(principal.userId())) {
            throw badRequest("Нельзя открыть личный диалог с самим собой");
        }
        requireActiveUser(otherUserId);

        List<UUID> candidateIds = members.findSharedRoomIds(principal.userId(), otherUserId);
        Optional<ChatRoom> existing = rooms.findAllById(candidateIds).stream()
                .filter(room -> room.getKind() == ChatRoom.Kind.DIRECT)
                .filter(room -> {
                    Set<UUID> ids = new HashSet<>(members.findUserIdsByRoomId(room.getId()));
                    return ids.size() == 2 && ids.contains(principal.userId()) && ids.contains(otherUserId);
                })
                .min(Comparator.comparing(ChatRoom::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        if (existing.isPresent()) {
            ChatRoom room = existing.get();
            return roomSummaryFor(principal.userId(), room);
        }

        ChatRoom room = new ChatRoom();
        room.setKind(ChatRoom.Kind.DIRECT);
        room.setTitle(null);
        room.setOwnerId(principal.userId());
        room.setReadOnly(false);
        room = rooms.save(room);

        members.saveAll(List.of(
                new ChatMember(room.getId(), principal.userId(), ChatMember.Role.OWNER),
                new ChatMember(room.getId(), otherUserId, ChatMember.Role.MEMBER)));
        return roomSummaryFor(principal.userId(), room);
    }

    public List<ChatDtos.MemberView> listMembers(ChatPrincipal principal, UUID roomId) {
        principal.require(ChatPrincipal.READ);
        loadRoom(roomId);
        requireMember(roomId, principal.userId());
        return members.findAllById_RoomId(roomId).stream()
                .sorted(Comparator.comparing(ChatMember::getJoinedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toMemberView)
                .toList();
    }

    public ChatDtos.RoomSummaryView addMembers(ChatPrincipal principal,
                                                UUID roomId,
                                                ChatDtos.AddMembersRequest request) {
        principal.require(ChatPrincipal.READ);
        ChatRoom room = loadRoom(roomId);
        ChatMember mine = requireMember(roomId, principal.userId());
        if (room.getKind() == ChatRoom.Kind.DIRECT) {
            throw unprocessable("В личный диалог нельзя добавить участников");
        }
        if (!canModerateRoom(principal, mine)) {
            throw forbidden("Добавлять участников может владелец, администратор комнаты или модератор");
        }
        if (request == null) {
            throw badRequest("Не указаны участники");
        }

        List<UUID> requested = normalizeIds(request.memberIds(), "memberIds");
        if (requested.isEmpty()) {
            throw badRequest("Не указаны участники");
        }
        requested.forEach(this::requireActiveUser);

        List<ChatMember> current = members.findAllById_RoomId(roomId);
        Set<UUID> currentIds = current.stream().map(ChatMember::getUserId).collect(Collectors.toSet());
        List<UUID> toAdd = requested.stream().filter(id -> !currentIds.contains(id)).toList();
        if (current.size() + toAdd.size() > MAX_ROOM_MEMBERS) {
            throw unprocessable("В комнате не может быть больше " + MAX_ROOM_MEMBERS + " участников");
        }
        if (!toAdd.isEmpty()) {
            members.saveAll(toAdd.stream()
                    .map(id -> new ChatMember(roomId, id, ChatMember.Role.MEMBER))
                    .toList());
        }
        return roomSummaryFor(principal.userId(), room);
    }

    public void leaveRoom(ChatPrincipal principal, UUID roomId) {
        principal.require(ChatPrincipal.READ);
        ChatRoom room = loadRoom(roomId);
        requireMember(roomId, principal.userId());
        long memberCount = members.countById_RoomId(roomId);
        if (principal.userId().equals(room.getOwnerId()) && memberCount > 1) {
            throw conflict("Владелец не может покинуть комнату, пока в ней есть другие участники");
        }
        members.deleteById_RoomIdAndId_UserId(roomId, principal.userId());
    }

    // ---------------------------------------------------------------------------------------------
    // Сообщения
    // ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ChatDtos.MessagePage history(ChatPrincipal principal,
                                        UUID roomId,
                                        UUID before,
                                        UUID threadRootId,
                                        int limit) {
        principal.require(ChatPrincipal.READ);
        loadRoom(roomId);
        requireMember(roomId, principal.userId());

        int safeLimit = Math.min(200, Math.max(1, limit));
        UUID effectiveThreadRoot = threadRootId == null
                ? null
                : normalizeThreadRoot(roomId, threadRootId);
        ChatMessage cursor = null;
        if (before != null) {
            cursor = loadMessage(before);
            if (!roomId.equals(cursor.getRoomId())) {
                throw badRequest("Курсор истории принадлежит другой комнате");
            }
        }

        List<ChatMessage> rows = messages.findHistory(
                roomId,
                effectiveThreadRoot,
                cursor == null ? null : cursor.getCreatedAt(),
                cursor == null ? null : cursor.getId(),
                PageRequest.of(0, safeLimit + 1));
        boolean hasMore = rows.size() > safeLimit;
        List<ChatMessage> visible = new ArrayList<>(rows.subList(0, Math.min(safeLimit, rows.size())));
        visible.sort(ChatService::compareMessagePosition);
        UUID nextBefore = hasMore && !visible.isEmpty() ? visible.get(0).getId() : null;
        return new ChatDtos.MessagePage(toMessageViews(visible), hasMore, nextBefore);
    }

    public ChatDtos.MessageView sendMessage(ChatPrincipal principal,
                                            UUID roomId,
                                            ChatDtos.SendMessageRequest request) {
        principal.require(ChatPrincipal.WRITE);
        ChatRoom room = loadRoom(roomId);
        ChatMember authorMembership = requireMember(roomId, principal.userId());
        if (request == null) {
            throw badRequest("Пустое сообщение");
        }

        String clientMessageId = normalizeNullable(request.clientMessageId());
        if (clientMessageId != null && clientMessageId.length() > 128) {
            throw badRequest("clientMessageId длиннее 128 символов");
        }
        if (clientMessageId != null) {
            Optional<ChatMessage> existing = messages.findByRoomIdAndAuthorIdAndClientMessageId(
                    roomId, principal.userId(), clientMessageId);
            if (existing.isPresent()) {
                return toMessageView(existing.get());
            }
        }

        requireCanWrite(principal, room, authorMembership);

        String fileRef = normalizeNullable(request.fileRef());
        if (fileRef != null && fileRef.length() > 256) {
            throw badRequest("fileRef длиннее 256 символов");
        }
        ChatMessage.Kind kind = request.kind() != null
                ? request.kind()
                : (fileRef != null ? ChatMessage.Kind.FILE : ChatMessage.Kind.TEXT);
        String body = validateAndNormalizeBody(kind, fileRef, request.bodyMarkdown(), principal);

        UUID effectiveThreadRoot = request.threadRootId() == null
                ? null
                : normalizeThreadRoot(roomId, request.threadRootId());

        Set<UUID> roomMemberIds = new HashSet<>(members.findUserIdsByRoomId(roomId));
        List<UUID> mentionedIds = normalizeIds(request.mentionedUserIds(), "mentionedUserIds").stream()
                .filter(roomMemberIds::contains)
                .filter(id -> !id.equals(principal.userId()))
                .toList();

        Instant now = Instant.now();
        ChatMessage message = new ChatMessage();
        message.setRoomId(roomId);
        message.setAuthorId(principal.userId());
        message.setKind(kind);
        message.setBodyMarkdown(body);
        message.setThreadRootId(effectiveThreadRoot);
        message.setFileRef(fileRef);
        message.setClientMessageId(clientMessageId);
        message.setCreatedAt(now);
        message = messages.saveAndFlush(message);

        if (!mentionedIds.isEmpty()) {
            UUID messageId = message.getId();
            mentions.saveAll(mentionedIds.stream()
                    .map(id -> new MessageMention(messageId, id))
                    .toList());
        }

        room.setLastMessageAt(message.getCreatedAt() == null ? now : message.getCreatedAt());
        rooms.save(room);
        authorMembership.setLastReadMessageId(message.getId());
        members.save(authorMembership);

        ChatDtos.MessageView view = toMessageView(message);
        events.publishMessageCreated(view, principal.userId());
        mentionedIds.forEach(id -> events.publishMention(view, id));
        return view;
    }

    public ChatDtos.MessageView editMessage(ChatPrincipal principal,
                                            UUID messageId,
                                            ChatDtos.EditMessageRequest request) {
        principal.require(ChatPrincipal.WRITE);
        ChatMessage message = loadMessage(messageId);
        ChatRoom room = loadRoom(message.getRoomId());
        ChatMember membership = requireMember(message.getRoomId(), principal.userId());
        if (!principal.userId().equals(message.getAuthorId())) {
            throw forbidden("Редактировать можно только собственное сообщение");
        }
        if (message.isDeleted()) {
            throw conflict("Удалённое сообщение нельзя редактировать");
        }
        requireCanWrite(principal, room, membership);
        if (request == null) {
            throw badRequest("Не указан новый текст сообщения");
        }
        String newBody = validateTextBody(request.bodyMarkdown());
        if (newBody.equals(message.getBodyMarkdown())) {
            return toMessageView(message);
        }

        Instant editedAt = Instant.now();
        editHistory.save(new MessageEditHistory(
                message.getId(), message.getBodyMarkdown(), editedAt));
        message.setBodyMarkdown(newBody);
        message.setEditedAt(editedAt);
        message = messages.save(message);

        ChatDtos.MessageView view = toMessageView(message);
        events.publishMessageUpdated(view, principal.userId());
        return view;
    }

    public ChatDtos.MessageView deleteMessage(ChatPrincipal principal, UUID messageId) {
        principal.require(ChatPrincipal.READ);
        ChatMessage message = loadMessage(messageId);
        ChatMember membership = requireMember(message.getRoomId(), principal.userId());
        if (!principal.userId().equals(message.getAuthorId())
                && !canModerateRoom(principal, membership)) {
            throw forbidden("Удалить сообщение может автор или модератор комнаты");
        }
        if (!message.isDeleted()) {
            message.setDeletedAt(Instant.now());
            message.setPinned(false);
            message = messages.save(message);
        }
        ChatDtos.MessageView view = toMessageView(message);
        events.publishMessageDeleted(view, principal.userId());
        return view;
    }

    public ChatDtos.MessageView toggleReaction(ChatPrincipal principal,
                                               UUID messageId,
                                               ChatDtos.ReactionRequest request) {
        principal.require(ChatPrincipal.READ);
        ChatMessage message = loadMessage(messageId);
        requireMember(message.getRoomId(), principal.userId());
        if (message.isDeleted()) {
            throw conflict("Нельзя ставить реакции на удалённое сообщение");
        }
        if (request == null || request.emoji() == null || request.emoji().isBlank()) {
            throw badRequest("Не указана реакция");
        }
        String emoji = request.emoji().trim();
        if (emoji.length() > 16) {
            throw badRequest("Реакция длиннее 16 символов");
        }

        Optional<MessageReaction> existing = reactions.findById_MessageIdAndId_UserIdAndId_Emoji(
                messageId, principal.userId(), emoji);
        if (existing.isPresent()) {
            reactions.delete(existing.get());
        } else {
            reactions.save(new MessageReaction(messageId, principal.userId(), emoji));
        }

        ChatDtos.MessageView view = toMessageView(message);
        events.publishMessageUpdated(view, principal.userId());
        return view;
    }

    public ChatDtos.MessageView setPinned(ChatPrincipal principal, UUID messageId, boolean pinned) {
        principal.require(ChatPrincipal.READ);
        ChatMessage message = loadMessage(messageId);
        ChatMember membership = requireMember(message.getRoomId(), principal.userId());
        if (!principal.userId().equals(message.getAuthorId())
                && !canModerateRoom(principal, membership)) {
            throw forbidden("Закреплять сообщения может автор или модератор комнаты");
        }
        if (pinned && message.isDeleted()) {
            throw conflict("Удалённое сообщение нельзя закрепить");
        }
        message.setPinned(pinned);
        message = messages.save(message);
        ChatDtos.MessageView view = toMessageView(message);
        events.publishMessageUpdated(view, principal.userId());
        return view;
    }

    public void markRead(ChatPrincipal principal, UUID roomId, ChatDtos.ReadRequest request) {
        principal.require(ChatPrincipal.READ);
        loadRoom(roomId);
        ChatMember membership = requireMember(roomId, principal.userId());
        if (request == null || request.lastReadMessageId() == null) {
            throw badRequest("Не указано сообщение прочтения");
        }
        ChatMessage target = loadMessage(request.lastReadMessageId());
        if (!roomId.equals(target.getRoomId())) {
            throw badRequest("Сообщение принадлежит другой комнате");
        }

        UUID currentId = membership.getLastReadMessageId();
        if (currentId != null) {
            Optional<ChatMessage> current = messages.findById(currentId)
                    .filter(message -> roomId.equals(message.getRoomId()));
            if (current.isPresent() && compareMessagePosition(target, current.get()) <= 0) {
                return;
            }
        }
        membership.setLastReadMessageId(target.getId());
        members.save(membership);
        events.publishRead(roomId, principal.userId(), target.getId());
    }

    @Transactional(readOnly = true)
    public List<ChatDtos.MessageView> search(ChatPrincipal principal,
                                             String query,
                                             UUID roomId,
                                             int limit) {
        principal.require(ChatPrincipal.READ);
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) {
            throw badRequest("Поисковый запрос должен содержать минимум 2 символа");
        }
        List<UUID> roomIds;
        if (roomId != null) {
            loadRoom(roomId);
            requireMember(roomId, principal.userId());
            roomIds = List.of(roomId);
        } else {
            roomIds = members.findAllById_UserId(principal.userId()).stream()
                    .map(ChatMember::getRoomId)
                    .distinct()
                    .toList();
        }
        if (roomIds.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.min(100, Math.max(1, limit));
        return toMessageViews(messages.searchInRooms(
                roomIds, escapeLike(q), PageRequest.of(0, safeLimit)));
    }

    // ---------------------------------------------------------------------------------------------
    // Realtime/internal authorization
    // ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ChatMember requireRealtimeAccess(ChatPrincipal principal, UUID roomId, boolean write) {
        principal.require(ChatPrincipal.READ);
        ChatRoom room = loadRoom(roomId);
        ChatMember membership = requireMember(roomId, principal.userId());
        if (write) {
            principal.require(ChatPrincipal.WRITE);
            requireCanWrite(principal, room, membership);
        }
        return membership;
    }

    public void typing(ChatPrincipal principal, UUID roomId) {
        requireRealtimeAccess(principal, roomId, true);
        events.publishTyping(roomId, principal.userId());
    }

    @Transactional(readOnly = true)
    public boolean isMember(UUID roomId, UUID userId) {
        return roomId != null && userId != null
                && members.existsById_RoomIdAndId_UserId(roomId, userId);
    }

    @Transactional(readOnly = true)
    public ChatMember requireMember(UUID roomId, UUID userId) {
        return members.findById_RoomIdAndId_UserId(roomId, userId)
                .orElseThrow(() -> forbidden("Пользователь не является участником комнаты"));
    }

    @Transactional(readOnly = true)
    public ChatDtos.MembershipInternalView internalMembership(UUID roomId, UUID userId) {
        Optional<ChatRoom> room = rooms.findById(roomId);
        Optional<ChatMember> membership = members.findById_RoomIdAndId_UserId(roomId, userId);
        return new ChatDtos.MembershipInternalView(
                roomId,
                userId,
                membership.isPresent(),
                membership.map(ChatMember::getRole).orElse(null),
                membership.map(ChatMember::getMutedUntil).orElse(null),
                room.map(ChatRoom::isReadOnly).orElse(false));
    }

    // ---------------------------------------------------------------------------------------------
    // Общие места: общий канал, права, загрузка и нормализация
    // ---------------------------------------------------------------------------------------------

    private ChatRoom ensureGeneralRoom(UUID userId) {
        Set<String> titles = new LinkedHashSet<>();
        String configured = normalizeOptionalTitle(generalRoomTitle);
        if (configured != null) {
            titles.add(configured);
        }
        titles.add("Общий");
        titles.add("General");
        titles.add("general");

        for (String title : titles) {
            Optional<ChatRoom> found = rooms.findFirstByKindAndTitleIgnoreCaseOrderByCreatedAtAsc(
                    ChatRoom.Kind.CHANNEL_PUBLIC, title);
            if (found.isPresent()) {
                ChatRoom room = found.get();
                if (!members.existsById_RoomIdAndId_UserId(room.getId(), userId)) {
                    members.save(new ChatMember(room.getId(), userId, ChatMember.Role.MEMBER));
                }
                return room;
            }
        }

        ChatRoom room = new ChatRoom();
        room.setKind(ChatRoom.Kind.CHANNEL_PUBLIC);
        room.setTitle(configured == null ? "Общий" : configured);
        room.setOwnerId(userId);
        room.setReadOnly(false);
        room = rooms.save(room);
        members.save(new ChatMember(room.getId(), userId, ChatMember.Role.OWNER));
        return room;
    }

    private ChatDtos.RoomSummaryView roomSummaryFor(UUID userId, ChatRoom room) {
        ChatMember mine = requireMember(room.getId(), userId);
        List<ChatMember> all = members.findAllById_RoomId(room.getId());
        ChatDtos.MessageView last = messages.findFirstByRoomIdOrderByCreatedAtDescIdDesc(room.getId())
                .map(this::toMessageView)
                .orElse(null);
        return toRoomSummary(room, mine, all, last);
    }

    private ChatDtos.RoomSummaryView toRoomSummary(ChatRoom room,
                                                   ChatMember mine,
                                                   List<ChatMember> allMembers,
                                                   ChatDtos.MessageView lastMessage) {
        List<UUID> memberIds = allMembers == null
                ? List.of()
                : allMembers.stream().map(ChatMember::getUserId).distinct().toList();
        ReadPosition position = readPosition(room.getId(), mine);
        long unread = mine == null ? 0 : messages.countUnreadAfter(
                room.getId(), mine.getUserId(), position.createdAt(), position.id());
        long mentionCount = mine == null ? 0 : mentions.countMentionsAfter(
                room.getId(), mine.getUserId(), position.createdAt(), position.id());
        return new ChatDtos.RoomSummaryView(
                room.getId(),
                room.getKind(),
                room.getTitle(),
                room.getOwnerId(),
                room.isReadOnly(),
                memberIds,
                mine == null ? null : mine.getRole(),
                memberIds.size(),
                capCount(unread),
                capCount(mentionCount),
                lastMessage,
                room.getCreatedAt(),
                room.getLastMessageAt());
    }

    private ReadPosition readPosition(UUID roomId, ChatMember membership) {
        if (membership == null || membership.getLastReadMessageId() == null) {
            return new ReadPosition(Instant.EPOCH, NIL_UUID);
        }
        return messages.findById(membership.getLastReadMessageId())
                .filter(message -> roomId.equals(message.getRoomId()))
                .map(message -> new ReadPosition(
                        message.getCreatedAt() == null ? Instant.EPOCH : message.getCreatedAt(),
                        message.getId()))
                .orElseGet(() -> new ReadPosition(Instant.EPOCH, NIL_UUID));
    }

    private ChatDtos.MessageView toMessageView(ChatMessage message) {
        return toMessageViews(List.of(message)).get(0);
    }

    private List<ChatDtos.MessageView> toMessageViews(List<ChatMessage> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<UUID> messageIds = source.stream().map(ChatMessage::getId).toList();

        Map<UUID, Map<String, List<UUID>>> reactionsByMessage = new HashMap<>();
        for (MessageReaction reaction : reactions.findAllById_MessageIdIn(messageIds)) {
            reactionsByMessage
                    .computeIfAbsent(reaction.getMessageId(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(reaction.getEmoji(), ignored -> new ArrayList<>())
                    .add(reaction.getUserId());
        }

        Map<UUID, List<UUID>> mentionsByMessage = new HashMap<>();
        for (MessageMention mention : mentions.findAllById_MessageIdIn(messageIds)) {
            mentionsByMessage
                    .computeIfAbsent(mention.getMessageId(), ignored -> new ArrayList<>())
                    .add(mention.getMentionedUserId());
        }

        return source.stream().map(message -> {
            boolean deleted = message.isDeleted();
            List<ChatDtos.ReactionView> reactionViews = reactionsByMessage
                    .getOrDefault(message.getId(), Map.of()).entrySet().stream()
                    .map(entry -> new ChatDtos.ReactionView(
                            entry.getKey(), entry.getValue().size(), entry.getValue()))
                    .sorted(Comparator.comparing(ChatDtos.ReactionView::emoji))
                    .toList();
            return new ChatDtos.MessageView(
                    message.getId(),
                    message.getRoomId(),
                    message.getAuthorId(),
                    message.getKind(),
                    deleted ? null : message.getBodyMarkdown(),
                    message.getThreadRootId(),
                    deleted ? null : message.getFileRef(),
                    message.isPinned(),
                    message.getEditedAt(),
                    deleted,
                    message.getClientMessageId(),
                    mentionsByMessage.getOrDefault(message.getId(), List.of()),
                    reactionViews,
                    message.getCreatedAt());
        }).toList();
    }

    private ChatDtos.MemberView toMemberView(ChatMember member) {
        return new ChatDtos.MemberView(
                member.getUserId(),
                member.getRole(),
                member.getMutedUntil(),
                member.getLastReadMessageId(),
                member.getJoinedAt());
    }

    private ChatRoom loadRoom(UUID roomId) {
        if (roomId == null) {
            throw badRequest("Не указана комната");
        }
        return rooms.findById(roomId)
                .orElseThrow(() -> notFound("Комната не найдена"));
    }

    private ChatMessage loadMessage(UUID messageId) {
        if (messageId == null) {
            throw badRequest("Не указано сообщение");
        }
        return messages.findById(messageId)
                .orElseThrow(() -> notFound("Сообщение не найдено"));
    }

    private UUID normalizeThreadRoot(UUID roomId, UUID threadRootId) {
        ChatMessage root = loadMessage(threadRootId);
        if (!roomId.equals(root.getRoomId())) {
            throw badRequest("Корень треда принадлежит другой комнате");
        }
        if (root.isDeleted()) {
            throw conflict("Нельзя отвечать в тред удалённого сообщения");
        }
        return root.getThreadRootId() != null ? root.getThreadRootId() : root.getId();
    }

    private void requireCanWrite(ChatPrincipal principal, ChatRoom room, ChatMember membership) {
        if (membership.getRole() == ChatMember.Role.READONLY) {
            throw forbidden("Роль участника не позволяет писать в эту комнату");
        }
        if (membership.isMuted(Instant.now())) {
            throw forbidden("Пользователь временно не может писать в эту комнату");
        }
        if (room.isReadOnly() && !canModerateRoom(principal, membership)) {
            throw forbidden("Комната доступна только для чтения");
        }
    }

    private boolean canModerateRoom(ChatPrincipal principal, ChatMember membership) {
        return principal.has(ChatPrincipal.MODERATE)
                || membership.getRole() == ChatMember.Role.OWNER
                || membership.getRole() == ChatMember.Role.ADMIN;
    }

    private String validateAndNormalizeBody(ChatMessage.Kind kind,
                                            String fileRef,
                                            String rawBody,
                                            ChatPrincipal principal) {
        if (rawBody != null && rawBody.length() > MAX_BODY_LENGTH) {
            throw badRequest("Сообщение длиннее " + MAX_BODY_LENGTH + " символов");
        }
        String body = rawBody == null || rawBody.isBlank() ? null : rawBody;
        switch (kind) {
            case TEXT -> {
                if (fileRef != null) {
                    throw badRequest("Для сообщения с файлом укажите kind FILE или IMAGE");
                }
                if (body == null) {
                    throw badRequest("Текст сообщения не может быть пустым");
                }
            }
            case FILE, IMAGE -> {
                principal.require(ChatPrincipal.SEND_FILES);
                if (fileRef == null) {
                    throw badRequest("Для файлового сообщения нужен fileRef");
                }
            }
            case VOICE, VIDEO -> {
                principal.require(ChatPrincipal.SEND_FILES);
                principal.require(ChatPrincipal.VOICE_VIDEO);
                if (fileRef == null) {
                    throw badRequest("Для голосового/видеосообщения нужен fileRef");
                }
            }
            case SYSTEM -> {
                principal.require(ChatPrincipal.MODERATE);
                if (fileRef != null) {
                    throw badRequest("Системное сообщение не может ссылаться на файл");
                }
                if (body == null) {
                    throw badRequest("Текст системного сообщения не может быть пустым");
                }
            }
        }
        return body;
    }

    private String validateTextBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw badRequest("Текст сообщения не может быть пустым");
        }
        if (rawBody.length() > MAX_BODY_LENGTH) {
            throw badRequest("Сообщение длиннее " + MAX_BODY_LENGTH + " символов");
        }
        return rawBody;
    }

    private void requireActiveUser(UUID userId) {
        Optional<AuthDirectoryClient.RemoteUser> user = directory.getUser(userId);
        if (user.isEmpty() || !user.get().active()) {
            throw unprocessable("Пользователь не найден или неактивен: " + userId);
        }
    }

    private List<UUID> normalizeIds(Collection<UUID> ids, String fieldName) {
        if (ids == null) {
            return List.of();
        }
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id == null) {
                throw badRequest(fieldName + " содержит пустой идентификатор");
            }
            result.add(id);
        }
        return new ArrayList<>(result);
    }

    private String normalizeOptionalTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String normalized = title.trim();
        if (normalized.length() > 256) {
            throw badRequest("Название комнаты длиннее 256 символов");
        }
        return normalized;
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Instant roomActivity(ChatRoom room) {
        if (room.getLastMessageAt() != null) {
            return room.getLastMessageAt();
        }
        return room.getCreatedAt() == null ? Instant.EPOCH : room.getCreatedAt();
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private int capCount(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }

    private static int compareMessagePosition(ChatMessage first, ChatMessage second) {
        Instant firstTime = first.getCreatedAt() == null ? Instant.EPOCH : first.getCreatedAt();
        Instant secondTime = second.getCreatedAt() == null ? Instant.EPOCH : second.getCreatedAt();
        int byTime = firstTime.compareTo(secondTime);
        if (byTime != 0) {
            return byTime;
        }
        String firstId = first.getId() == null ? "" : first.getId().toString();
        String secondId = second.getId() == null ? "" : second.getId().toString();
        return firstId.compareTo(secondId);
    }

    private ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    private ResponseStatusException forbidden(String detail) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, detail);
    }

    private ResponseStatusException notFound(String detail) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, detail);
    }

    private ResponseStatusException conflict(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, detail);
    }

    private ResponseStatusException unprocessable(String detail) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }

    private record ReadPosition(Instant createdAt, UUID id) {
    }
}
