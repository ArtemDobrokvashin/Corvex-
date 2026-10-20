package ru.corvex.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.corvex.chat.domain.MessageReaction;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageReactionRepository
        extends JpaRepository<MessageReaction, MessageReaction.Id> {

    List<MessageReaction> findAllById_MessageIdIn(Collection<UUID> messageIds);

    Optional<MessageReaction> findById_MessageIdAndId_UserIdAndId_Emoji(
            UUID messageId, UUID userId, String emoji);
}
