package ru.corvex.chat.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDomainTest {

    private static final UUID ROOM_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");

    @Test
    void memberIdentityMuteAndDefaults() {
        ChatMember empty = new ChatMember();
        assertNull(empty.getRoomId());
        assertNull(empty.getUserId());
        empty.setRole(null);
        empty.initJoinedAt();
        assertEquals(ChatMember.Role.MEMBER, empty.getRole());
        assertNotNull(empty.getJoinedAt());

        Instant now = Instant.parse("2026-10-20T09:00:00Z");
        ChatMember member = new ChatMember(ROOM_ID, USER_ID, ChatMember.Role.ADMIN);
        member.setJoinedAt(now);
        member.setMutedUntil(now.plusSeconds(1));
        member.setLastReadMessageId(MESSAGE_ID);
        member.initJoinedAt();
        assertEquals(now, member.getJoinedAt());
        assertEquals(ROOM_ID, member.getRoomId());
        assertEquals(USER_ID, member.getUserId());
        assertTrue(member.isMuted(now));
        assertFalse(member.isMuted(now.plusSeconds(1)));
        member.setMutedUntil(null);
        assertFalse(member.isMuted(now));

        ChatMember.Id left = new ChatMember.Id(ROOM_ID, USER_ID);
        ChatMember.Id right = new ChatMember.Id();
        right.setRoomId(ROOM_ID);
        right.setUserId(USER_ID);
        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        assertNotEquals(left, new ChatMember.Id(ROOM_ID, MESSAGE_ID));
        assertEquals(List.of("OWNER", "ADMIN", "MEMBER", "READONLY"),
                java.util.Arrays.stream(ChatMember.Role.values()).map(Enum::name).toList());
    }

    @Test
    void roomTimestampInitialization() {
        ChatRoom room = new ChatRoom();
        room.setKind(ChatRoom.Kind.GROUP);
        room.setTitle("Team");
        room.setOwnerId(USER_ID);
        room.setReadOnly(true);
        room.initTimestamps();
        assertNotNull(room.getCreatedAt());
        assertEquals(room.getCreatedAt(), room.getLastMessageAt());
        assertEquals(ChatRoom.Kind.GROUP, room.getKind());
        assertTrue(room.isReadOnly());

        Instant created = Instant.parse("2026-10-20T08:00:00Z");
        Instant last = Instant.parse("2026-10-20T09:00:00Z");
        ChatRoom existing = new ChatRoom();
        existing.setCreatedAt(created);
        existing.initTimestamps();
        assertEquals(created, existing.getLastMessageAt());
        existing.setLastMessageAt(last);
        existing.initTimestamps();
        assertEquals(last, existing.getLastMessageAt());
        assertEquals(List.of("DIRECT", "GROUP", "CHANNEL_PUBLIC", "CHANNEL_PRIVATE", "SYSTEM"),
                java.util.Arrays.stream(ChatRoom.Kind.values()).map(Enum::name).toList());
    }

    @Test
    void messageDeletionAndDefaults() {
        ChatMessage message = new ChatMessage();
        assertEquals(ChatMessage.Kind.TEXT, message.getKind());
        message.setKind(null);
        message.initCreatedAt();
        assertEquals(ChatMessage.Kind.TEXT, message.getKind());
        assertNotNull(message.getCreatedAt());
        assertFalse(message.isDeleted());

        Instant created = Instant.parse("2026-10-20T09:00:00Z");
        message.setCreatedAt(created);
        message.setKind(ChatMessage.Kind.FILE);
        message.setDeletedAt(created.plusSeconds(1));
        message.initCreatedAt();
        assertEquals(created, message.getCreatedAt());
        assertEquals(ChatMessage.Kind.FILE, message.getKind());
        assertTrue(message.isDeleted());
        assertEquals(List.of("TEXT", "FILE", "IMAGE", "VOICE", "VIDEO", "SYSTEM"),
                java.util.Arrays.stream(ChatMessage.Kind.values()).map(Enum::name).toList());
    }

    @Test
    void editHistoryMentionAndReactionKeys() {
        Instant edited = Instant.parse("2026-10-20T09:05:00Z");
        MessageEditHistory history = new MessageEditHistory(MESSAGE_ID, null, edited);
        assertEquals("", history.getOldBody());
        assertEquals(MESSAGE_ID, history.getMessageId());
        assertEquals(edited, history.getEditedAt());
        assertNull(new MessageEditHistory().getMessageId());
        assertNull(new MessageEditHistory().getEditedAt());
        MessageEditHistory.Id historyId = new MessageEditHistory.Id();
        historyId.setMessageId(MESSAGE_ID);
        historyId.setEditedAt(edited);
        assertEquals(new MessageEditHistory.Id(MESSAGE_ID, edited), historyId);

        MessageMention mention = new MessageMention(MESSAGE_ID, USER_ID);
        assertEquals(MESSAGE_ID, mention.getMessageId());
        assertEquals(USER_ID, mention.getMentionedUserId());
        assertNull(new MessageMention().getMessageId());
        assertNull(new MessageMention().getMentionedUserId());
        MessageMention.Id mentionId = new MessageMention.Id();
        mentionId.setMessageId(MESSAGE_ID);
        mentionId.setMentionedUserId(USER_ID);
        assertEquals(new MessageMention.Id(MESSAGE_ID, USER_ID), mentionId);

        MessageReaction reaction = new MessageReaction(MESSAGE_ID, USER_ID, "👍");
        assertEquals(MESSAGE_ID, reaction.getMessageId());
        assertEquals(USER_ID, reaction.getUserId());
        assertEquals("👍", reaction.getEmoji());
        assertNull(new MessageReaction().getMessageId());
        assertNull(new MessageReaction().getUserId());
        assertNull(new MessageReaction().getEmoji());
        MessageReaction.Id reactionId = new MessageReaction.Id();
        reactionId.setMessageId(MESSAGE_ID);
        reactionId.setUserId(USER_ID);
        reactionId.setEmoji("👍");
        assertEquals(new MessageReaction.Id(MESSAGE_ID, USER_ID, "👍"), reactionId);
        assertEquals(new MessageReaction.Id(MESSAGE_ID, USER_ID, "👍").hashCode(), reactionId.hashCode());
    }

    @Test
    void entityAccessorsRoundTripMutableFields() {
        ChatMessage message = new ChatMessage();
        message.setId(MESSAGE_ID);
        message.setRoomId(ROOM_ID);
        message.setAuthorId(USER_ID);
        message.setBodyMarkdown("body");
        message.setThreadRootId(MESSAGE_ID);
        message.setFileRef("file:1");
        message.setPinned(true);
        message.setEditedAt(Instant.now());
        message.setClientMessageId("client");

        assertEquals(MESSAGE_ID, message.getId());
        assertEquals(ROOM_ID, message.getRoomId());
        assertEquals(USER_ID, message.getAuthorId());
        assertEquals("body", message.getBodyMarkdown());
        assertEquals("file:1", message.getFileRef());
        assertTrue(message.isPinned());
        assertEquals("client", message.getClientMessageId());
    }
}
