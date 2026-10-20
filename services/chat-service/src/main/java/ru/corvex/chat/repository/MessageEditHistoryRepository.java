package ru.corvex.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.corvex.chat.domain.MessageEditHistory;

public interface MessageEditHistoryRepository
        extends JpaRepository<MessageEditHistory, MessageEditHistory.Id> {
}
