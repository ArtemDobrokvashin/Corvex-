package ru.corvex.chat.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.chat.domain.ChatMessage;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    Optional<ChatMessage> findByRoomIdAndAuthorIdAndClientMessageId(
            UUID roomId, UUID authorId, String clientMessageId);

    Optional<ChatMessage> findFirstByRoomIdOrderByCreatedAtDescIdDesc(UUID roomId);

    /**
     * История назад от курсора. Пара (created_at, id) даёт стабильный порядок даже для
     * нескольких сообщений в одну миллисекунду; сервис запрашивает limit+1 и разворачивает страницу.
     */
    @Query("""
            select m
            from ChatMessage m
            where m.roomId = :roomId
              and ((:threadRootId is null and m.threadRootId is null)
                   or m.threadRootId = :threadRootId)
              and (:cursorCreatedAt is null
                   or m.createdAt < :cursorCreatedAt
                   or (m.createdAt = :cursorCreatedAt and m.id < :cursorId))
            order by m.createdAt desc, m.id desc
            """)
    List<ChatMessage> findHistory(@Param("roomId") UUID roomId,
                                  @Param("threadRootId") UUID threadRootId,
                                  @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                  @Param("cursorId") UUID cursorId,
                                  Pageable pageable);

    @Query("""
            select m
            from ChatMessage m
            where m.roomId in :roomIds
              and m.deletedAt is null
              and lower(coalesce(m.bodyMarkdown, '')) like lower(concat('%', :query, '%'))
            order by m.createdAt desc, m.id desc
            """)
    List<ChatMessage> searchInRooms(@Param("roomIds") Collection<UUID> roomIds,
                                    @Param("query") String query,
                                    Pageable pageable);

    @Query("""
            select count(m)
            from ChatMessage m
            where m.roomId = :roomId
              and m.authorId <> :userId
              and m.deletedAt is null
              and (m.createdAt > :afterCreatedAt
                   or (m.createdAt = :afterCreatedAt and m.id > :afterId))
            """)
    long countUnreadAfter(@Param("roomId") UUID roomId,
                          @Param("userId") UUID userId,
                          @Param("afterCreatedAt") Instant afterCreatedAt,
                          @Param("afterId") UUID afterId);
}
