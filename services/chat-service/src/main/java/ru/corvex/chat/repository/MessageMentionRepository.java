package ru.corvex.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.chat.domain.MessageMention;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MessageMentionRepository
        extends JpaRepository<MessageMention, MessageMention.Id> {

    List<MessageMention> findAllById_MessageIdIn(Collection<UUID> messageIds);

    @Query(value = """
            select count(*)
            from message_mentions mm
            join chat_messages m on m.id = mm.message_id
            where mm.mentioned_user_id = :userId
              and m.room_id = :roomId
              and m.deleted_at is null
              and (m.created_at > :afterCreatedAt
                   or (m.created_at = :afterCreatedAt and m.id > :afterId))
            """, nativeQuery = true)
    long countMentionsAfter(@Param("roomId") UUID roomId,
                            @Param("userId") UUID userId,
                            @Param("afterCreatedAt") Instant afterCreatedAt,
                            @Param("afterId") UUID afterId);
}
