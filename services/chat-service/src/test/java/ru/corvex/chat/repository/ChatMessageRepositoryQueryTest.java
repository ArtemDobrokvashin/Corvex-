package ru.corvex.chat.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageRepositoryQueryTest {

    @Test
    void historyQueryUsesPresenceFlagsInsteadOfNullableParameterNullChecks() throws Exception {
        Method method = ChatMessageRepository.class.getMethod(
                "findHistory",
                UUID.class,
                boolean.class,
                UUID.class,
                boolean.class,
                Instant.class,
                UUID.class,
                Pageable.class);
        Query queryAnnotation = method.getAnnotation(Query.class);

        assertNotNull(queryAnnotation);
        String query = queryAnnotation.value();
        assertTrue(query.contains(":threadSpecified = false"));
        assertTrue(query.contains(":threadSpecified = true"));
        assertTrue(query.contains(":hasCursor = false"));
        assertTrue(query.contains("m.threadRootId = :threadRootId"));
        assertTrue(query.contains("m.createdAt < :cursorCreatedAt"));
        assertTrue(query.contains("m.id < :cursorId"));

        // PostgreSQL 42P18: standalone temporal "? is null" не даёт серверу вывести тип.
        assertFalse(query.contains(":threadRootId is null"));
        assertFalse(query.contains(":cursorCreatedAt is null"));
        assertFalse(query.contains(":cursorId is null"));
    }
}
