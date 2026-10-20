package ru.corvex.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.corvex.chat.domain.ChatRoom;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, UUID> {

    List<ChatRoom> findByKindOrderByCreatedAtAsc(ChatRoom.Kind kind);

    Optional<ChatRoom> findFirstByKindAndTitleIgnoreCaseOrderByCreatedAtAsc(
            ChatRoom.Kind kind, String title);
}
