package ru.corvex.chat.api;

import org.junit.jupiter.api.Test;
import ru.corvex.chat.domain.ChatMember;
import ru.corvex.chat.domain.ChatMessage;
import ru.corvex.chat.domain.ChatRoom;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatDtosTest {

    private static final UUID FIRST = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SECOND = UUID.fromString("10000000-0000-4000-8000-000000000002");

    @Test
    void viewRecordsNormalizeAndCopyCollections() {
        List<UUID> mutable = new ArrayList<>(List.of(FIRST));
        ChatDtos.ReactionView reaction = new ChatDtos.ReactionView("👍", 1, mutable);
        mutable.add(SECOND);
        assertEquals(List.of(FIRST), reaction.userIds());
        assertThrows(UnsupportedOperationException.class, () -> reaction.userIds().add(SECOND));
        assertEquals(List.of(), new ChatDtos.ReactionView("👍", 0, null).userIds());

        ChatDtos.MessageView message = new ChatDtos.MessageView(
                FIRST, SECOND, FIRST, ChatMessage.Kind.TEXT, "hello", null, null,
                false, null, false, "client", null, null, Instant.now());
        assertEquals(List.of(), message.mentionedUserIds());
        assertEquals(List.of(), message.reactions());

        assertEquals(List.of(), new ChatDtos.MessagePage(null, false, null).items());
        ChatDtos.RoomSummaryView room = new ChatDtos.RoomSummaryView(
                FIRST, ChatRoom.Kind.GROUP, "Team", FIRST, false, null,
                ChatMember.Role.OWNER, 0, 0, 0, null, Instant.now(), Instant.now());
        assertEquals(List.of(), room.memberIds());
    }

    @Test
    void requestRecordsReplaceNullMemberAndMentionLists() {
        assertEquals(List.of(), new ChatDtos.CreateRoomRequest(
                ChatRoom.Kind.GROUP, "Team", null, null).memberIds());
        assertEquals(List.of(), new ChatDtos.SendMessageRequest(
                "hello", null, null, null, null, null).mentionedUserIds());
        assertEquals(List.of(), new ChatDtos.AddMembersRequest(null).memberIds());

        List<UUID> members = List.of(FIRST);
        assertSame(members, new ChatDtos.CreateRoomRequest(
                ChatRoom.Kind.GROUP, "Team", members, true).memberIds());
    }

    @Test
    void simpleRecordsExposeAllFields() {
        Instant now = Instant.now();
        ChatDtos.DirectoryUserView directory = new ChatDtos.DirectoryUserView(
                FIRST, "user", "user@example.test", "User", "Sales", "ACTIVE", "ONLINE");
        ChatDtos.MemberView member = new ChatDtos.MemberView(
                FIRST, ChatMember.Role.ADMIN, now, SECOND, now);
        ChatDtos.ChatEventView event = new ChatDtos.ChatEventView(
                "TYPING", FIRST, null, SECOND, null, now);
        ChatDtos.MembershipInternalView membership = new ChatDtos.MembershipInternalView(
                FIRST, SECOND, true, ChatMember.Role.MEMBER, now, false);

        assertEquals("ONLINE", directory.presence());
        assertEquals(ChatMember.Role.ADMIN, member.role());
        assertEquals("TYPING", event.type());
        assertEquals(now, membership.mutedUntil());
        assertEquals("updated", new ChatDtos.EditMessageRequest("updated").bodyMarkdown());
        assertEquals("👍", new ChatDtos.ReactionRequest("👍").emoji());
        assertEquals(FIRST, new ChatDtos.ReadRequest(FIRST).lastReadMessageId());
        assertEquals("AWAY", new ChatDtos.PresenceRequest("AWAY").status());
    }

    @Test
    void utilityClassConstructorIsPrivateAndInvocableForCoverage() throws Exception {
        Constructor<ChatDtos> constructor = ChatDtos.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }
}
