package ru.corvex.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.chat.domain.ChatMember;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatMemberRepository extends JpaRepository<ChatMember, ChatMember.Id> {

    Optional<ChatMember> findById_RoomIdAndId_UserId(UUID roomId, UUID userId);

    List<ChatMember> findAllById_UserId(UUID userId);

    List<ChatMember> findAllById_RoomId(UUID roomId);

    List<ChatMember> findAllById_RoomIdIn(Collection<UUID> roomIds);

    boolean existsById_RoomIdAndId_UserId(UUID roomId, UUID userId);

    long countById_RoomId(UUID roomId);

    void deleteById_RoomIdAndId_UserId(UUID roomId, UUID userId);

    @Query("select m.id.userId from ChatMember m where m.id.roomId = :roomId order by m.joinedAt asc")
    List<UUID> findUserIdsByRoomId(@Param("roomId") UUID roomId);

    /**
     * Кандидаты в личный диалог: комнаты, где встречаются оба пользователя.
     * Итог дополнительно фильтруется по kind=DIRECT и точному числу участников в сервисе.
     */
    @Query("""
            select m.id.roomId
            from ChatMember m
            where m.id.userId in (:firstUserId, :secondUserId)
            group by m.id.roomId
            having count(distinct m.id.userId) = 2
            """)
    List<UUID> findSharedRoomIds(@Param("firstUserId") UUID firstUserId,
                                 @Param("secondUserId") UUID secondUserId);
}
