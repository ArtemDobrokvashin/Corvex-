package ru.corvex.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID NEW_ROOM_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OTHER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID THIRD_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID MESSAGE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ROOT_MESSAGE_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");

    @Mock
    private ChatRoomRepository rooms;
    @Mock
    private ChatMemberRepository members;
    @Mock
    private ChatMessageRepository messages;
    @Mock
    private MessageEditHistoryRepository editHistory;
    @Mock
    private MessageReactionRepository reactions;
    @Mock
    private MessageMentionRepository mentions;
    @Mock
    private AuthDirectoryClient directory;
    @Mock
    private ChatEventPublisher events;

    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(rooms, members, messages, editHistory, reactions, mentions, directory, events);
        ReflectionTestUtils.setField(service, "generalRoomTitle", "Общий");
        when(reactions.findAllById_MessageIdIn(any())).thenReturn(List.of());
        when(mentions.findAllById_MessageIdIn(any())).thenReturn(List.of());
        when(directory.getUser(any())).thenAnswer(invocation -> Optional.of(
                remoteUser(invocation.getArgument(0), "ACTIVE")));
        when(rooms.save(any(ChatRoom.class))).thenAnswer(invocation -> {
            ChatRoom room = invocation.getArgument(0);
            if (room.getId() == null) {
                room.setId(NEW_ROOM_ID);
            }
            if (room.getCreatedAt() == null) {
                room.setCreatedAt(Instant.parse("2026-10-20T08:00:00Z"));
            }
            if (room.getLastMessageAt() == null) {
                room.setLastMessageAt(room.getCreatedAt());
            }
            return room;
        });
        when(members.save(any(ChatMember.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(members.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messages.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(MESSAGE_ID);
            }
            return message;
        });
        when(messages.saveAndFlush(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(MESSAGE_ID);
            }
            return message;
        });
    }

    @Test
    void sendMessageRejectsNonMember() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room(false)));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(principal(), ROOM_ID, request("hello", "client-1", List.of())));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void sendMessageWithSameClientIdReturnsExistingMessageWithoutDuplicateOrEvent() {
        ChatMessage existing = existingMessage();
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room(false)));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(membership(ChatMember.Role.MEMBER)));
        when(messages.findByRoomIdAndAuthorIdAndClientMessageId(ROOM_ID, USER_ID, "client-1"))
                .thenReturn(Optional.of(existing));

        ChatDtos.MessageView result = service.sendMessage(
                principal(), ROOM_ID, request("changed after reconnect", "client-1", List.of()));

        assertEquals(MESSAGE_ID, result.id());
        assertEquals("original", result.bodyMarkdown());
        assertEquals("client-1", result.clientMessageId());
        verify(messages, never()).saveAndFlush(any());
        verify(events, never()).publishMessageCreated(any(), any());
    }

    @Test
    void sendMessagePersistsMessageUpdatesRoomAndAuthorReadPositionAndPublishesEvents() {
        ChatRoom room = room(false);
        ChatMember membership = membership(ChatMember.Role.MEMBER);
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(membership));
        when(messages.findByRoomIdAndAuthorIdAndClientMessageId(ROOM_ID, USER_ID, "client-2"))
                .thenReturn(Optional.empty());
        when(members.findUserIdsByRoomId(ROOM_ID)).thenReturn(List.of(USER_ID, OTHER_ID));
        when(messages.saveAndFlush(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(MESSAGE_ID);
            }
            return message;
        });
        when(mentions.findAllById_MessageIdIn(any()))
                .thenReturn(List.of(new MessageMention(MESSAGE_ID, OTHER_ID)));

        ChatDtos.MessageView result = service.sendMessage(
                principal(), ROOM_ID, request("Hello **team**", "client-2", List.of(OTHER_ID)));

        assertEquals(MESSAGE_ID, result.id());
        assertEquals("Hello **team**", result.bodyMarkdown());
        assertEquals(List.of(OTHER_ID), result.mentionedUserIds());
        assertEquals(MESSAGE_ID, membership.getLastReadMessageId());
        assertEquals(result.createdAt(), room.getLastMessageAt());
        verify(mentions).saveAll(any());
        verify(events).publishMessageCreated(result, USER_ID);
        verify(events).publishMention(result, OTHER_ID);
    }

    @Test
    void sendMessageRejectsMutedMember() {
        ChatMember membership = membership(ChatMember.Role.MEMBER);
        membership.setMutedUntil(Instant.now().plusSeconds(60));
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room(false)));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(membership));
        when(messages.findByRoomIdAndAuthorIdAndClientMessageId(ROOM_ID, USER_ID, "client-3"))
                .thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(principal(), ROOM_ID, request("hello", "client-3", List.of())));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void sendMessageRejectsOrdinaryMemberInReadOnlyRoom() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room(true)));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(membership(ChatMember.Role.MEMBER)));
        when(messages.findByRoomIdAndAuthorIdAndClientMessageId(ROOM_ID, USER_ID, "client-4"))
                .thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(principal(), ROOM_ID, request("hello", "client-4", List.of())));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void listRoomsCreatesConfiguredGeneralRoomAndOwnerMembershipWhenMissing() {
        ReflectionTestUtils.setField(service, "generalRoomTitle", " Company General ");
        when(rooms.findFirstByKindAndTitleIgnoreCaseOrderByCreatedAtAsc(any(), any()))
                .thenReturn(Optional.empty());
        when(members.findAllById_UserId(USER_ID)).thenReturn(List.of());

        assertEquals(List.of(), service.listRooms(principal()));

        ArgumentCaptor<ChatRoom> roomCaptor = ArgumentCaptor.forClass(ChatRoom.class);
        verify(rooms).save(roomCaptor.capture());
        assertEquals(ChatRoom.Kind.CHANNEL_PUBLIC, roomCaptor.getValue().getKind());
        assertEquals("Company General", roomCaptor.getValue().getTitle());
        assertEquals(USER_ID, roomCaptor.getValue().getOwnerId());
        ArgumentCaptor<ChatMember> memberCaptor = ArgumentCaptor.forClass(ChatMember.class);
        verify(members).save(memberCaptor.capture());
        assertEquals(ChatMember.Role.OWNER, memberCaptor.getValue().getRole());
    }

    @Test
    void listRoomsJoinsExistingGeneralRoomFoundByFallbackTitle() {
        ReflectionTestUtils.setField(service, "generalRoomTitle", " ");
        ChatRoom general = room(ROOM_ID, ChatRoom.Kind.CHANNEL_PUBLIC, OTHER_ID, "General", false,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"));
        when(rooms.findFirstByKindAndTitleIgnoreCaseOrderByCreatedAtAsc(
                eq(ChatRoom.Kind.CHANNEL_PUBLIC), any()))
                .thenAnswer(invocation -> "General".equals(invocation.getArgument(1))
                        ? Optional.of(general) : Optional.empty());
        when(members.existsById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(false);
        when(members.findAllById_UserId(USER_ID)).thenReturn(List.of());

        service.listRooms(principal());

        ArgumentCaptor<ChatMember> captor = ArgumentCaptor.forClass(ChatMember.class);
        verify(members).save(captor.capture());
        assertEquals(ROOM_ID, captor.getValue().getRoomId());
        assertEquals(ChatMember.Role.MEMBER, captor.getValue().getRole());
    }

    @Test
    void listRoomsSortsByActivityCapsCountersAndMasksDeletedLastMessage() {
        ChatRoom general = room(ROOM_ID, ChatRoom.Kind.CHANNEL_PUBLIC, OTHER_ID, "Общий", false,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"));
        ChatRoom group = room(NEW_ROOM_ID, ChatRoom.Kind.GROUP, USER_ID, "Project", false,
                Instant.parse("2026-10-03T00:00:00Z"), Instant.parse("2026-10-04T00:00:00Z"));
        ChatMember generalMine = member(ROOM_ID, USER_ID, ChatMember.Role.MEMBER);
        ChatMember groupMine = member(NEW_ROOM_ID, USER_ID, ChatMember.Role.OWNER);
        groupMine.setLastReadMessageId(MESSAGE_ID);
        ChatMember other = member(NEW_ROOM_ID, OTHER_ID, ChatMember.Role.MEMBER);
        ChatMessage deletedLast = message(UUID.randomUUID(), ROOM_ID, OTHER_ID, "secret",
                Instant.parse("2026-10-02T00:00:00Z"));
        deletedLast.setDeletedAt(Instant.parse("2026-10-02T01:00:00Z"));
        deletedLast.setFileRef("file:secret");
        ChatMessage groupLast = message(MESSAGE_ID, NEW_ROOM_ID, OTHER_ID, "latest",
                Instant.parse("2026-10-04T00:00:00Z"));

        when(rooms.findFirstByKindAndTitleIgnoreCaseOrderByCreatedAtAsc(
                ChatRoom.Kind.CHANNEL_PUBLIC, "Общий")).thenReturn(Optional.of(general));
        when(members.existsById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(true);
        when(members.findAllById_UserId(USER_ID)).thenReturn(List.of(generalMine, groupMine));
        when(rooms.findAllById(List.of(ROOM_ID, NEW_ROOM_ID))).thenReturn(List.of(general, group));
        when(members.findAllById_RoomIdIn(List.of(ROOM_ID, NEW_ROOM_ID)))
                .thenReturn(List.of(generalMine, groupMine, other));
        when(messages.findFirstByRoomIdOrderByCreatedAtDescIdDesc(ROOM_ID))
                .thenReturn(Optional.of(deletedLast));
        when(messages.findFirstByRoomIdOrderByCreatedAtDescIdDesc(NEW_ROOM_ID))
                .thenReturn(Optional.of(groupLast));
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(groupLast));
        when(messages.countUnreadAfter(eq(ROOM_ID), eq(USER_ID), any(), any())).thenReturn(5L);
        when(messages.countUnreadAfter(eq(NEW_ROOM_ID), eq(USER_ID), any(), any()))
                .thenReturn((long) Integer.MAX_VALUE + 10L);
        when(mentions.countMentionsAfter(eq(NEW_ROOM_ID), eq(USER_ID), any(), any())).thenReturn(2L);

        List<ChatDtos.RoomSummaryView> result = service.listRooms(principal());

        assertEquals(List.of(NEW_ROOM_ID, ROOM_ID), result.stream().map(ChatDtos.RoomSummaryView::id).toList());
        assertEquals(Integer.MAX_VALUE, result.get(0).unreadCount());
        assertEquals(2, result.get(0).mentionCount());
        assertEquals("latest", result.get(0).lastMessage().bodyMarkdown());
        assertEquals(5, result.get(1).unreadCount());
        assertNull(result.get(1).lastMessage().bodyMarkdown());
        assertNull(result.get(1).lastMessage().fileRef());
        assertTrue(result.get(1).lastMessage().deleted());
    }

    @Test
    void createRoomCreatesGroupWithDefaultTitleAndFilteredMembers() {
        ChatDtos.CreateRoomRequest request = new ChatDtos.CreateRoomRequest(
                ChatRoom.Kind.GROUP, " ", List.of(USER_ID, OTHER_ID, OTHER_ID), null);

        ChatDtos.RoomSummaryView result = service.createRoom(
                principalWith(ChatPrincipal.READ, ChatPrincipal.CREATE_GROUP), request);

        assertEquals(NEW_ROOM_ID, result.id());
        assertEquals("Групповой чат", result.title());
        assertEquals(List.of(USER_ID, OTHER_ID), result.memberIds());
        assertEquals(ChatMember.Role.OWNER, result.myRole());
        assertFalse(result.readOnly());
        verify(directory).getUser(OTHER_ID);
    }

    @Test
    void createRoomAppliesChannelAndSystemReadOnlyDefaults() {
        ChatDtos.RoomSummaryView channel = service.createRoom(
                principalWith(ChatPrincipal.READ, ChatPrincipal.CREATE_CHANNEL),
                new ChatDtos.CreateRoomRequest(ChatRoom.Kind.CHANNEL_PRIVATE, " Announcements ", List.of(), null));
        ChatDtos.RoomSummaryView system = service.createRoom(
                principalWith(ChatPrincipal.READ, ChatPrincipal.MODERATE),
                new ChatDtos.CreateRoomRequest(ChatRoom.Kind.SYSTEM, "System", List.of(), null));

        assertEquals("Announcements", channel.title());
        assertFalse(channel.readOnly());
        assertTrue(system.readOnly());
    }

    @Test
    void createRoomValidatesRequestKindAndCreationPermissions() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.createRoom(principal(), null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.createRoom(principal(),
                        new ChatDtos.CreateRoomRequest(null, "x", List.of(), null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.createRoom(principal(),
                        new ChatDtos.CreateRoomRequest(ChatRoom.Kind.DIRECT, "x", List.of(), null)))
                .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.createRoom(principalWith(ChatPrincipal.READ),
                        new ChatDtos.CreateRoomRequest(ChatRoom.Kind.GROUP, "x", List.of(), null)))
                .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.createRoom(
                        principalWith(ChatPrincipal.READ, ChatPrincipal.CREATE_CHANNEL),
                        new ChatDtos.CreateRoomRequest(ChatRoom.Kind.CHANNEL_PUBLIC, " ", List.of(), null)))
                .getStatusCode());
    }

    @Test
    void createRoomValidatesTitleMembersActivityAndCapacity() {
        ChatPrincipal creator = principalWith(ChatPrincipal.READ, ChatPrincipal.CREATE_GROUP);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.createRoom(creator, new ChatDtos.CreateRoomRequest(
                        ChatRoom.Kind.GROUP, "x".repeat(257), List.of(), null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.createRoom(creator, new ChatDtos.CreateRoomRequest(
                        ChatRoom.Kind.GROUP, "Team", Arrays.asList(OTHER_ID, null), null))).getStatusCode());

        when(directory.getUser(OTHER_ID)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> service.createRoom(creator, new ChatDtos.CreateRoomRequest(
                        ChatRoom.Kind.GROUP, "Team", List.of(OTHER_ID), null))).getStatusCode());

        List<UUID> tooMany = IntStream.range(0, 1000).mapToObj(i -> new UUID(0L, i + 1L)).toList();
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> service.createRoom(creator, new ChatDtos.CreateRoomRequest(
                        ChatRoom.Kind.GROUP, "Team", tooMany, null))).getStatusCode());
    }

    @Test
    void openDirectReturnsOldestExactDirectRoom() {
        UUID olderRoomId = UUID.fromString("88888888-8888-4888-8888-888888888888");
        ChatRoom group = room(ROOM_ID, ChatRoom.Kind.GROUP, USER_ID, "Group", false,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"));
        ChatRoom newer = room(NEW_ROOM_ID, ChatRoom.Kind.DIRECT, USER_ID, null, false,
                Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"));
        ChatRoom older = room(olderRoomId, ChatRoom.Kind.DIRECT, USER_ID, null, false,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"));
        when(members.findSharedRoomIds(USER_ID, OTHER_ID))
                .thenReturn(List.of(ROOM_ID, NEW_ROOM_ID, olderRoomId));
        when(rooms.findAllById(List.of(ROOM_ID, NEW_ROOM_ID, olderRoomId)))
                .thenReturn(List.of(group, newer, older));
        when(members.findUserIdsByRoomId(NEW_ROOM_ID)).thenReturn(List.of(USER_ID, OTHER_ID));
        when(members.findUserIdsByRoomId(olderRoomId)).thenReturn(List.of(USER_ID, OTHER_ID));
        ChatMember mine = member(olderRoomId, USER_ID, ChatMember.Role.OWNER);
        stubRoomSummary(older, mine,
                List.of(mine, member(olderRoomId, OTHER_ID, ChatMember.Role.MEMBER)), Optional.empty());

        ChatDtos.RoomSummaryView result = service.openDirect(principal(), OTHER_ID);

        assertEquals(olderRoomId, result.id());
        verify(rooms, never()).save(any(ChatRoom.class));
    }

    @Test
    void openDirectCreatesRoomWhenNoExactDirectExistsAndValidatesCompanion() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.openDirect(principal(), null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.openDirect(principal(), USER_ID)).getStatusCode());

        when(members.findSharedRoomIds(USER_ID, OTHER_ID)).thenReturn(List.of());
        when(rooms.findAllById(List.of())).thenReturn(List.of());
        ChatMember mine = member(NEW_ROOM_ID, USER_ID, ChatMember.Role.OWNER);
        ChatRoom createdShape = room(NEW_ROOM_ID, ChatRoom.Kind.DIRECT, USER_ID, null, false,
                Instant.parse("2026-10-20T08:00:00Z"), Instant.parse("2026-10-20T08:00:00Z"));
        stubRoomSummary(createdShape, mine,
                List.of(mine, member(NEW_ROOM_ID, OTHER_ID, ChatMember.Role.MEMBER)), Optional.empty());

        ChatDtos.RoomSummaryView result = service.openDirect(principal(), OTHER_ID);

        assertEquals(NEW_ROOM_ID, result.id());
        assertEquals(ChatRoom.Kind.DIRECT, result.kind());
        assertEquals(List.of(USER_ID, OTHER_ID), result.memberIds());
    }

    @Test
    void openDirectRejectsInactiveCompanion() {
        when(directory.getUser(OTHER_ID)).thenReturn(Optional.of(remoteUser(OTHER_ID, "BLOCKED")));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> service.openDirect(principal(), OTHER_ID)).getStatusCode());
    }

    @Test
    void listMembersSortsByJoinedAtWithNullsLastAndRequiresMembership() {
        ChatRoom room = room(false);
        ChatMember early = member(ROOM_ID, THIRD_ID, ChatMember.Role.MEMBER);
        early.setJoinedAt(Instant.parse("2026-10-01T00:00:00Z"));
        ChatMember late = member(ROOM_ID, OTHER_ID, ChatMember.Role.ADMIN);
        late.setJoinedAt(Instant.parse("2026-10-03T00:00:00Z"));
        ChatMember unknown = member(ROOM_ID, USER_ID, ChatMember.Role.OWNER);
        unknown.setJoinedAt(null);
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(unknown));
        when(members.findAllById_RoomId(ROOM_ID)).thenReturn(List.of(late, unknown, early));

        assertEquals(List.of(THIRD_ID, OTHER_ID, USER_ID),
                service.listMembers(principal(), ROOM_ID).stream().map(ChatDtos.MemberView::userId).toList());

        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.listMembers(principal(), ROOM_ID)).getStatusCode());
    }

    @Test
    void addMembersAddsOnlyNewMembersAndSkipsSaveWhenNothingChanges() {
        ChatRoom room = room(false);
        ChatMember mine = membership(ChatMember.Role.OWNER);
        ChatMember other = member(ROOM_ID, OTHER_ID, ChatMember.Role.MEMBER);
        ChatMember third = member(ROOM_ID, THIRD_ID, ChatMember.Role.MEMBER);
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(mine));
        when(members.findAllById_RoomId(ROOM_ID))
                .thenReturn(List.of(mine, other), List.of(mine, other, third), List.of(mine, other, third));
        when(messages.findFirstByRoomIdOrderByCreatedAtDescIdDesc(ROOM_ID)).thenReturn(Optional.empty());

        ChatDtos.RoomSummaryView result = service.addMembers(principal(), ROOM_ID,
                new ChatDtos.AddMembersRequest(List.of(OTHER_ID, THIRD_ID, THIRD_ID)));

        assertEquals(List.of(USER_ID, OTHER_ID, THIRD_ID), result.memberIds());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMember>> captor = ArgumentCaptor.forClass(List.class);
        verify(members).saveAll(captor.capture());
        assertEquals(List.of(THIRD_ID), captor.getValue().stream().map(ChatMember::getUserId).toList());

        service.addMembers(principal(), ROOM_ID,
                new ChatDtos.AddMembersRequest(List.of(OTHER_ID, THIRD_ID)));
        verify(members, org.mockito.Mockito.times(1)).saveAll(any());
    }

    @Test
    void addMembersValidatesRoomRoleRequestActivityAndCapacity() {
        ChatRoom room = room(false);
        ChatMember ordinary = membership(ChatMember.Role.MEMBER);
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(ordinary));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.addMembers(principal(), ROOM_ID,
                        new ChatDtos.AddMembersRequest(List.of(OTHER_ID)))).getStatusCode());

        ChatMember owner = membership(ChatMember.Role.OWNER);
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(owner));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.addMembers(principal(), ROOM_ID, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.addMembers(principal(), ROOM_ID,
                        new ChatDtos.AddMembersRequest(null))).getStatusCode());

        room.setKind(ChatRoom.Kind.DIRECT);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> service.addMembers(principal(), ROOM_ID,
                        new ChatDtos.AddMembersRequest(List.of(OTHER_ID)))).getStatusCode());
        room.setKind(ChatRoom.Kind.GROUP);

        List<ChatMember> full = IntStream.range(0, 1000)
                .mapToObj(i -> member(ROOM_ID, new UUID(0L, i + 1L), ChatMember.Role.MEMBER))
                .toList();
        when(members.findAllById_RoomId(ROOM_ID)).thenReturn(full);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> service.addMembers(principal(), ROOM_ID,
                        new ChatDtos.AddMembersRequest(List.of(THIRD_ID)))).getStatusCode());
    }

    @Test
    void globalModeratorCanAddMembersAsOrdinaryRoomMember() {
        ChatRoom room = room(false);
        ChatMember mine = membership(ChatMember.Role.MEMBER);
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(mine));
        when(members.findAllById_RoomId(ROOM_ID)).thenReturn(List.of(mine),
                List.of(mine, member(ROOM_ID, OTHER_ID, ChatMember.Role.MEMBER)));
        when(messages.findFirstByRoomIdOrderByCreatedAtDescIdDesc(ROOM_ID)).thenReturn(Optional.empty());

        ChatDtos.RoomSummaryView result = service.addMembers(
                principalWith(ChatPrincipal.READ, ChatPrincipal.MODERATE), ROOM_ID,
                new ChatDtos.AddMembersRequest(List.of(OTHER_ID)));

        assertEquals(List.of(USER_ID, OTHER_ID), result.memberIds());
    }

    @Test
    void leaveRoomProtectsOwnerWithOtherMembersButAllowsSoleOwnerAndOrdinaryMember() {
        ChatRoom room = room(false);
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(membership(ChatMember.Role.OWNER)));
        when(members.countById_RoomId(ROOM_ID)).thenReturn(2L);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.leaveRoom(principal(), ROOM_ID)).getStatusCode());

        when(members.countById_RoomId(ROOM_ID)).thenReturn(1L);
        service.leaveRoom(principal(), ROOM_ID);

        room.setOwnerId(OTHER_ID);
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(membership(ChatMember.Role.MEMBER)));
        when(members.countById_RoomId(ROOM_ID)).thenReturn(3L);
        service.leaveRoom(principal(), ROOM_ID);
        verify(members, org.mockito.Mockito.times(2))
                .deleteById_RoomIdAndId_UserId(ROOM_ID, USER_ID);
    }

    @Test
    void historyClampsLimitReversesPageAndReturnsNextCursor() {
        stubRoomMember();
        ChatMessage first = message(UUID.fromString("11111111-1111-4111-8111-000000000001"),
                ROOM_ID, USER_ID, "one", Instant.parse("2026-10-20T09:00:00Z"));
        ChatMessage second = message(UUID.fromString("11111111-1111-4111-8111-000000000002"),
                ROOM_ID, USER_ID, "two", Instant.parse("2026-10-20T09:01:00Z"));
        ChatMessage third = message(UUID.fromString("11111111-1111-4111-8111-000000000003"),
                ROOM_ID, USER_ID, "three", Instant.parse("2026-10-20T09:02:00Z"));
        when(messages.findHistory(eq(ROOM_ID), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(List.of(third, second, first));

        ChatDtos.MessagePage page = service.history(principal(), ROOM_ID, null, null, 2);

        assertEquals(List.of(second.getId(), third.getId()),
                page.items().stream().map(ChatDtos.MessageView::id).toList());
        assertTrue(page.hasMore());
        assertEquals(second.getId(), page.nextBefore());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(messages).findHistory(eq(ROOM_ID), eq(null), eq(null), eq(null), pageable.capture());
        assertEquals(PageRequest.of(0, 3), pageable.getValue());

        when(messages.findHistory(eq(ROOM_ID), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(List.of());
        service.history(principal(), ROOM_ID, null, null, 999);
        service.history(principal(), ROOM_ID, null, null, 0);
        ArgumentCaptor<Pageable> allPageables = ArgumentCaptor.forClass(Pageable.class);
        verify(messages, org.mockito.Mockito.times(3))
                .findHistory(eq(ROOM_ID), eq(null), eq(null), eq(null), allPageables.capture());
        assertEquals(List.of(3, 201, 2),
                allPageables.getAllValues().stream().map(Pageable::getPageSize).toList());
    }

    @Test
    void historyNormalizesReplyThreadRootAndCursor() {
        stubRoomMember();
        ChatMessage root = message(ROOT_MESSAGE_ID, ROOM_ID, USER_ID, "root",
                Instant.parse("2026-10-20T09:00:00Z"));
        ChatMessage reply = message(MESSAGE_ID, ROOM_ID, OTHER_ID, "reply",
                Instant.parse("2026-10-20T09:05:00Z"));
        reply.setThreadRootId(ROOT_MESSAGE_ID);
        ChatMessage cursor = message(THIRD_ID, ROOM_ID, USER_ID, "cursor",
                Instant.parse("2026-10-20T09:10:00Z"));
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(reply));
        when(messages.findById(THIRD_ID)).thenReturn(Optional.of(cursor));
        when(messages.findHistory(eq(ROOM_ID), eq(ROOT_MESSAGE_ID),
                eq(cursor.getCreatedAt()), eq(THIRD_ID), any(Pageable.class))).thenReturn(List.of(root));

        ChatDtos.MessagePage page = service.history(principal(), ROOM_ID, THIRD_ID, MESSAGE_ID, 10);

        assertEquals(ROOT_MESSAGE_ID, page.items().get(0).id());
        assertFalse(page.hasMore());
        assertNull(page.nextBefore());
    }

    @Test
    void historyRejectsForeignThreadAndCursorAndDeletedRoot() {
        stubRoomMember();
        ChatMessage foreign = message(MESSAGE_ID, NEW_ROOM_ID, USER_ID, "foreign", Instant.now());
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(foreign));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.history(principal(), ROOM_ID, null, MESSAGE_ID, 10)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.history(principal(), ROOM_ID, MESSAGE_ID, null, 10)).getStatusCode());

        ChatMessage deletedRoot = message(ROOT_MESSAGE_ID, ROOM_ID, USER_ID, "deleted", Instant.now());
        deletedRoot.setDeletedAt(Instant.now());
        when(messages.findById(ROOT_MESSAGE_ID)).thenReturn(Optional.of(deletedRoot));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.history(principal(), ROOM_ID, null, ROOT_MESSAGE_ID, 10)).getStatusCode());
    }

    @Test
    void sendValidatesRequestIdentifiersAndMessageKinds() {
        stubRoomMember();
        ChatPrincipal writer = principal();
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(writer, ROOM_ID, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(writer, ROOM_ID, new ChatDtos.SendMessageRequest(
                        "hello", null, "x".repeat(129), ChatMessage.Kind.TEXT, null, List.of())))
                .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(writer, ROOM_ID, new ChatDtos.SendMessageRequest(
                        "hello", null, null, ChatMessage.Kind.TEXT, "file:1", List.of()))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(writer, ROOM_ID, request(" ", null, List.of()))).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(writer, ROOM_ID, new ChatDtos.SendMessageRequest(
                        null, null, null, ChatMessage.Kind.FILE, "file:1", List.of()))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(
                        principalWith(ChatPrincipal.READ, ChatPrincipal.WRITE, ChatPrincipal.SEND_FILES),
                        ROOM_ID, new ChatDtos.SendMessageRequest(
                                null, null, null, ChatMessage.Kind.IMAGE, null, List.of()))).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(
                        principalWith(ChatPrincipal.READ, ChatPrincipal.WRITE, ChatPrincipal.SEND_FILES),
                        ROOM_ID, new ChatDtos.SendMessageRequest(
                                null, null, null, ChatMessage.Kind.VOICE, "file:v", List.of()))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(
                        principalWith(ChatPrincipal.READ, ChatPrincipal.WRITE, ChatPrincipal.MODERATE),
                        ROOM_ID, new ChatDtos.SendMessageRequest(
                                "announcement", null, null, ChatMessage.Kind.SYSTEM, "file:x", List.of())))
                .getStatusCode());
    }

    @Test
    void sendFileAndVideoNormalizeKindAndAllowFileOnlyBody() {
        stubRoomMember();
        when(members.findUserIdsByRoomId(ROOM_ID)).thenReturn(List.of(USER_ID));

        ChatDtos.MessageView file = service.sendMessage(
                principalWith(ChatPrincipal.READ, ChatPrincipal.WRITE, ChatPrincipal.SEND_FILES),
                ROOM_ID, new ChatDtos.SendMessageRequest(
                        " ", null, " file-client ", null, " file:42 ", null));
        ChatDtos.MessageView video = service.sendMessage(
                principalWith(ChatPrincipal.READ, ChatPrincipal.WRITE,
                        ChatPrincipal.SEND_FILES, ChatPrincipal.VOICE_VIDEO),
                ROOM_ID, new ChatDtos.SendMessageRequest(
                        null, null, null, ChatMessage.Kind.VIDEO, "file:video", List.of()));

        assertEquals(ChatMessage.Kind.FILE, file.kind());
        assertNull(file.bodyMarkdown());
        assertEquals("file:42", file.fileRef());
        assertEquals("file-client", file.clientMessageId());
        assertEquals(ChatMessage.Kind.VIDEO, video.kind());
    }

    @Test
    void sendNormalizesNestedThreadAndFiltersMentions() {
        ChatMember author = stubRoomMember();
        ChatMessage reply = message(MESSAGE_ID, ROOM_ID, OTHER_ID, "reply", Instant.now());
        reply.setThreadRootId(ROOT_MESSAGE_ID);
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(reply));
        when(members.findUserIdsByRoomId(ROOM_ID)).thenReturn(List.of(USER_ID, OTHER_ID));
        when(messages.saveAndFlush(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage msg = invocation.getArgument(0);
            msg.setId(THIRD_ID);
            return msg;
        });
        when(mentions.findAllById_MessageIdIn(List.of(THIRD_ID)))
                .thenReturn(List.of(new MessageMention(THIRD_ID, OTHER_ID)));

        ChatDtos.MessageView result = service.sendMessage(principal(), ROOM_ID,
                new ChatDtos.SendMessageRequest("answer", MESSAGE_ID, null,
                        ChatMessage.Kind.TEXT, null, List.of(USER_ID, THIRD_ID, OTHER_ID, OTHER_ID)));

        assertEquals(ROOT_MESSAGE_ID, result.threadRootId());
        assertEquals(List.of(OTHER_ID), result.mentionedUserIds());
        assertNotNull(author.getLastReadMessageId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageMention>> captor = ArgumentCaptor.forClass(List.class);
        verify(mentions).saveAll(captor.capture());
        assertEquals(List.of(OTHER_ID), captor.getValue().stream().map(MessageMention::getMentionedUserId).toList());
    }

    @Test
    void sendHonorsReadonlyRoleExpiredMuteAndModeratorWriteRules() {
        ChatMember readonly = member(ROOM_ID, USER_ID, ChatMember.Role.READONLY);
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room(false)));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(readonly));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(
                        principalWith(ChatPrincipal.READ, ChatPrincipal.WRITE, ChatPrincipal.MODERATE),
                        ROOM_ID, request("x", null, List.of()))).getStatusCode());

        ChatMember expiredMute = membership(ChatMember.Role.MEMBER);
        expiredMute.setMutedUntil(Instant.now().minusSeconds(1));
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room(true)));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(expiredMute));
        when(members.findUserIdsByRoomId(ROOM_ID)).thenReturn(List.of(USER_ID));
        ChatDtos.MessageView result = service.sendMessage(
                principalWith(ChatPrincipal.READ, ChatPrincipal.WRITE, ChatPrincipal.MODERATE),
                ROOM_ID, request("moderator announcement", null, List.of()));
        assertEquals("moderator announcement", result.bodyMarkdown());
    }

    @Test
    void editMessageStoresHistoryAndReturnsSameBodyWithoutWrite() {
        ChatMessage message = existingMessage();
        stubMessageContext(message, membership(ChatMember.Role.MEMBER));

        ChatDtos.MessageView same = service.editMessage(principal(), MESSAGE_ID,
                new ChatDtos.EditMessageRequest("original"));
        assertEquals("original", same.bodyMarkdown());
        verify(editHistory, never()).save(any());

        ChatDtos.MessageView changed = service.editMessage(principal(), MESSAGE_ID,
                new ChatDtos.EditMessageRequest("updated"));
        assertEquals("updated", changed.bodyMarkdown());
        assertNotNull(changed.editedAt());
        ArgumentCaptor<MessageEditHistory> history = ArgumentCaptor.forClass(MessageEditHistory.class);
        verify(editHistory).save(history.capture());
        assertEquals("original", history.getValue().getOldBody());
        assertEquals(MESSAGE_ID, history.getValue().getMessageId());
        verify(events).publishMessageUpdated(changed, USER_ID);
    }

    @Test
    void editMessageRejectsForeignDeletedAndInvalidText() {
        ChatMessage message = existingMessage();
        message.setAuthorId(OTHER_ID);
        stubMessageContext(message, membership(ChatMember.Role.MEMBER));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.editMessage(principal(), MESSAGE_ID,
                        new ChatDtos.EditMessageRequest("new"))).getStatusCode());

        message.setAuthorId(USER_ID);
        message.setDeletedAt(Instant.now());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.editMessage(principal(), MESSAGE_ID,
                        new ChatDtos.EditMessageRequest("new"))).getStatusCode());

        message.setDeletedAt(null);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.editMessage(principal(), MESSAGE_ID, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.editMessage(principal(), MESSAGE_ID,
                        new ChatDtos.EditMessageRequest(" "))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.editMessage(principal(), MESSAGE_ID,
                        new ChatDtos.EditMessageRequest("x".repeat(32_001)))).getStatusCode());
    }

    @Test
    void deleteMessageMasksContentAndAllowsAuthorModeratorAndIdempotentRepeat() {
        ChatMessage message = existingMessage();
        message.setFileRef("file:secret");
        message.setPinned(true);
        stubMessageContext(message, membership(ChatMember.Role.MEMBER));

        ChatDtos.MessageView deleted = service.deleteMessage(principal(), MESSAGE_ID);
        assertTrue(deleted.deleted());
        assertNull(deleted.bodyMarkdown());
        assertNull(deleted.fileRef());
        assertFalse(message.isPinned());
        assertNotNull(message.getDeletedAt());
        verify(events).publishMessageDeleted(deleted, USER_ID);

        service.deleteMessage(principal(), MESSAGE_ID);
        verify(messages, org.mockito.Mockito.times(1)).save(message);

        ChatMessage foreign = message(THIRD_ID, ROOM_ID, OTHER_ID, "foreign", Instant.now());
        when(messages.findById(THIRD_ID)).thenReturn(Optional.of(foreign));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.deleteMessage(principal(), THIRD_ID)).getStatusCode());
        service.deleteMessage(
                principalWith(ChatPrincipal.READ, ChatPrincipal.MODERATE), THIRD_ID);
        assertTrue(foreign.isDeleted());
    }

    @Test
    void toggleReactionAddsRemovesAndValidatesInput() {
        ChatMessage message = existingMessage();
        stubMessageContext(message, membership(ChatMember.Role.MEMBER));
        when(reactions.findById_MessageIdAndId_UserIdAndId_Emoji(MESSAGE_ID, USER_ID, "👍"))
                .thenReturn(Optional.empty());

        service.toggleReaction(principal(), MESSAGE_ID, new ChatDtos.ReactionRequest(" 👍 "));
        ArgumentCaptor<MessageReaction> captor = ArgumentCaptor.forClass(MessageReaction.class);
        verify(reactions).save(captor.capture());
        assertEquals("👍", captor.getValue().getEmoji());

        MessageReaction existing = captor.getValue();
        when(reactions.findById_MessageIdAndId_UserIdAndId_Emoji(MESSAGE_ID, USER_ID, "👍"))
                .thenReturn(Optional.of(existing));
        service.toggleReaction(principal(), MESSAGE_ID, new ChatDtos.ReactionRequest("👍"));
        verify(reactions).delete(existing);

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.toggleReaction(principal(), MESSAGE_ID, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.toggleReaction(principal(), MESSAGE_ID,
                        new ChatDtos.ReactionRequest("x".repeat(17)))).getStatusCode());
        message.setDeletedAt(Instant.now());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.toggleReaction(principal(), MESSAGE_ID,
                        new ChatDtos.ReactionRequest("👍"))).getStatusCode());
    }

    @Test
    void setPinnedEnforcesAuthorOrModeratorAndDeletedRule() {
        ChatMessage message = existingMessage();
        stubMessageContext(message, membership(ChatMember.Role.MEMBER));

        assertTrue(service.setPinned(principal(), MESSAGE_ID, true).pinned());
        assertFalse(service.setPinned(principal(), MESSAGE_ID, false).pinned());

        message.setAuthorId(OTHER_ID);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.setPinned(principal(), MESSAGE_ID, true)).getStatusCode());
        assertTrue(service.setPinned(
                principalWith(ChatPrincipal.READ, ChatPrincipal.MODERATE), MESSAGE_ID, true).pinned());

        message.setDeletedAt(Instant.now());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.setPinned(principalWith(ChatPrincipal.READ, ChatPrincipal.MODERATE),
                        MESSAGE_ID, true)).getStatusCode());
        assertFalse(service.setPinned(principalWith(ChatPrincipal.READ, ChatPrincipal.MODERATE),
                MESSAGE_ID, false).pinned());
    }

    @Test
    void markReadMovesForwardPublishesAndNeverMovesBackward() {
        ChatMember mine = stubRoomMember();
        ChatMessage older = message(UUID.fromString("11111111-1111-4111-8111-000000000001"),
                ROOM_ID, OTHER_ID, "older", Instant.parse("2026-10-20T09:00:00Z"));
        ChatMessage newer = message(UUID.fromString("11111111-1111-4111-8111-000000000002"),
                ROOM_ID, OTHER_ID, "newer", Instant.parse("2026-10-20T09:00:00Z"));
        when(messages.findById(older.getId())).thenReturn(Optional.of(older));
        when(messages.findById(newer.getId())).thenReturn(Optional.of(newer));

        service.markRead(principal(), ROOM_ID, new ChatDtos.ReadRequest(newer.getId()));
        assertEquals(newer.getId(), mine.getLastReadMessageId());
        verify(events).publishRead(ROOM_ID, USER_ID, newer.getId());

        service.markRead(principal(), ROOM_ID, new ChatDtos.ReadRequest(older.getId()));
        assertEquals(newer.getId(), mine.getLastReadMessageId());
        verify(events, org.mockito.Mockito.times(1)).publishRead(any(), any(), any());
    }

    @Test
    void markReadValidatesRoomAndIgnoresForeignStoredPosition() {
        ChatMember mine = stubRoomMember();
        ChatMessage target = message(MESSAGE_ID, ROOM_ID, OTHER_ID, "target",
                Instant.parse("2026-10-20T09:00:00Z"));
        ChatMessage foreign = message(THIRD_ID, NEW_ROOM_ID, OTHER_ID, "foreign",
                Instant.parse("2026-10-20T10:00:00Z"));
        mine.setLastReadMessageId(THIRD_ID);
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(target));
        when(messages.findById(THIRD_ID)).thenReturn(Optional.of(foreign));

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.markRead(principal(), ROOM_ID, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.markRead(principal(), ROOM_ID,
                        new ChatDtos.ReadRequest(null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.markRead(principal(), ROOM_ID,
                        new ChatDtos.ReadRequest(THIRD_ID))).getStatusCode());

        service.markRead(principal(), ROOM_ID, new ChatDtos.ReadRequest(MESSAGE_ID));
        assertEquals(MESSAGE_ID, mine.getLastReadMessageId());
    }

    @Test
    void searchEscapesLikePatternsAndRestrictsRooms() {
        stubRoomMember();
        ChatMessage found = existingMessage();
        when(messages.searchInRooms(eq(List.of(ROOM_ID)), any(), any(Pageable.class)))
                .thenReturn(List.of(found));

        List<ChatDtos.MessageView> result = service.search(principal(), " a\\%_b ", ROOM_ID, 999);

        assertEquals(MESSAGE_ID, result.get(0).id());
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(messages).searchInRooms(eq(List.of(ROOM_ID)), query.capture(), pageable.capture());
        assertEquals("a\\\\\\%\\_b", query.getValue());
        assertEquals(100, pageable.getValue().getPageSize());
    }

    @Test
    void searchValidatesQueryAndHandlesNoMemberships() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.search(principal(), " x ", null, 10)).getStatusCode());
        when(members.findAllById_UserId(USER_ID)).thenReturn(List.of());
        assertEquals(List.of(), service.search(principal(), "hello", null, 0));
        verify(messages, never()).searchInRooms(any(), any(), any());
    }

    @Test
    void realtimeAccessTypingMembershipAndInternalView() {
        ChatMember mine = stubRoomMember();
        assertEquals(mine, service.requireRealtimeAccess(principal(), ROOM_ID, false));
        assertEquals(mine, service.requireRealtimeAccess(principal(), ROOM_ID, true));
        service.typing(principal(), ROOM_ID);
        verify(events).publishTyping(ROOM_ID, USER_ID);

        when(members.existsById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(true);
        assertTrue(service.isMember(ROOM_ID, USER_ID));
        assertFalse(service.isMember(null, USER_ID));
        assertFalse(service.isMember(ROOM_ID, null));

        ChatDtos.MembershipInternalView internal = service.internalMembership(ROOM_ID, USER_ID);
        assertTrue(internal.member());
        assertEquals(ChatMember.Role.MEMBER, internal.role());
        assertFalse(internal.roomReadOnly());

        when(rooms.findById(ROOM_ID)).thenReturn(Optional.empty());
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.empty());
        ChatDtos.MembershipInternalView absent = service.internalMembership(ROOM_ID, USER_ID);
        assertFalse(absent.member());
        assertNull(absent.role());
        assertFalse(absent.roomReadOnly());
    }

    @Test
    void messageViewsGroupReactionsMentionsAndMaskDeletedContent() {
        stubRoomMember();
        ChatMessage deleted = existingMessage();
        deleted.setDeletedAt(Instant.now());
        deleted.setFileRef("file:hidden");
        when(messages.findHistory(eq(ROOM_ID), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(List.of(deleted));
        when(reactions.findAllById_MessageIdIn(List.of(MESSAGE_ID))).thenReturn(List.of(
                new MessageReaction(MESSAGE_ID, THIRD_ID, "fire"),
                new MessageReaction(MESSAGE_ID, OTHER_ID, ":)"),
                new MessageReaction(MESSAGE_ID, USER_ID, "fire")));
        when(mentions.findAllById_MessageIdIn(List.of(MESSAGE_ID)))
                .thenReturn(List.of(new MessageMention(MESSAGE_ID, OTHER_ID)));

        ChatDtos.MessageView view = service.history(principal(), ROOM_ID, null, null, 10).items().get(0);

        assertNull(view.bodyMarkdown());
        assertNull(view.fileRef());
        assertEquals(List.of(OTHER_ID), view.mentionedUserIds());
        assertEquals(List.of(":)", "fire"), view.reactions().stream().map(ChatDtos.ReactionView::emoji).toList());
        assertEquals(2L, view.reactions().get(1).count());
    }

    @Test
    void loadingHelpersRejectNullAndMissingEntities() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.listMembers(principal(), null)).getStatusCode());
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.listMembers(principal(), ROOM_ID)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.deleteMessage(principal(), null)).getStatusCode());
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.deleteMessage(principal(), MESSAGE_ID)).getStatusCode());
    }

    private ChatPrincipal principal() {
        return new ChatPrincipal(USER_ID, Set.of(ChatPrincipal.READ, ChatPrincipal.WRITE), null);
    }

    private ChatPrincipal principalWith(String... permissions) {
        return new ChatPrincipal(USER_ID, Set.of(permissions), null);
    }

    private ChatMember stubRoomMember() {
        ChatRoom group = room(false);
        ChatMember mine = membership(ChatMember.Role.MEMBER);
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(group));
        when(members.findById_RoomIdAndId_UserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(mine));
        return mine;
    }

    private void stubMessageContext(ChatMessage message, ChatMember mine) {
        when(messages.findById(message.getId())).thenReturn(Optional.of(message));
        when(rooms.findById(message.getRoomId())).thenReturn(Optional.of(room(false)));
        when(members.findById_RoomIdAndId_UserId(message.getRoomId(), USER_ID))
                .thenReturn(Optional.of(mine));
    }

    private void stubRoomSummary(ChatRoom room, ChatMember mine, List<ChatMember> all,
                                 Optional<ChatMessage> last) {
        when(members.findById_RoomIdAndId_UserId(room.getId(), USER_ID)).thenReturn(Optional.of(mine));
        when(members.findAllById_RoomId(room.getId())).thenReturn(all);
        when(messages.findFirstByRoomIdOrderByCreatedAtDescIdDesc(room.getId())).thenReturn(last);
    }

    private AuthDirectoryClient.RemoteUser remoteUser(UUID id, String status) {
        return new AuthDirectoryClient.RemoteUser(
                id, "user-" + id, id + "@example.test", "User " + id,
                "LOCAL", status, "Sales", List.of("USER"), List.of("CHAT_READ"),
                1_000L, 0L, false, false, null,
                Instant.parse("2026-10-01T00:00:00Z"));
    }

    private ChatRoom room(UUID id, ChatRoom.Kind kind, UUID owner, String title, boolean readOnly,
                          Instant createdAt, Instant lastMessageAt) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        room.setKind(kind);
        room.setOwnerId(owner);
        room.setTitle(title);
        room.setReadOnly(readOnly);
        room.setCreatedAt(createdAt);
        room.setLastMessageAt(lastMessageAt);
        return room;
    }

    private ChatMember member(UUID roomId, UUID userId, ChatMember.Role role) {
        ChatMember member = new ChatMember(roomId, userId, role);
        member.setJoinedAt(Instant.parse("2026-10-20T08:00:00Z"));
        return member;
    }

    private ChatMessage message(UUID id, UUID roomId, UUID author, String body, Instant createdAt) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setRoomId(roomId);
        message.setAuthorId(author);
        message.setKind(ChatMessage.Kind.TEXT);
        message.setBodyMarkdown(body);
        message.setCreatedAt(createdAt);
        return message;
    }

    private ChatRoom room(boolean readOnly) {
        ChatRoom room = new ChatRoom();
        room.setId(ROOM_ID);
        room.setKind(ChatRoom.Kind.GROUP);
        room.setTitle("Team");
        room.setOwnerId(USER_ID);
        room.setReadOnly(readOnly);
        room.setCreatedAt(Instant.parse("2026-10-20T08:00:00Z"));
        room.setLastMessageAt(Instant.parse("2026-10-20T08:00:00Z"));
        return room;
    }

    private ChatMember membership(ChatMember.Role role) {
        ChatMember member = new ChatMember(ROOM_ID, USER_ID, role);
        member.setJoinedAt(Instant.parse("2026-10-20T08:00:00Z"));
        return member;
    }

    private ChatMessage existingMessage() {
        ChatMessage message = new ChatMessage();
        message.setId(MESSAGE_ID);
        message.setRoomId(ROOM_ID);
        message.setAuthorId(USER_ID);
        message.setKind(ChatMessage.Kind.TEXT);
        message.setBodyMarkdown("original");
        message.setClientMessageId("client-1");
        message.setCreatedAt(Instant.parse("2026-10-20T09:00:00Z"));
        return message;
    }

    private ChatDtos.SendMessageRequest request(String body, String clientMessageId, List<UUID> mentioned) {
        return new ChatDtos.SendMessageRequest(
                body, null, clientMessageId, ChatMessage.Kind.TEXT, null, mentioned);
    }
}
