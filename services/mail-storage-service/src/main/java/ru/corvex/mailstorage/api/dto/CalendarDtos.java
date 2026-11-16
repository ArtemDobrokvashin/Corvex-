package ru.corvex.mailstorage.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Контракт календаря. Время — ISO-8601 instant (UTC), зона — IANA id события. */
public final class CalendarDtos {

    private CalendarDtos() {
    }

    public record EventView(
            UUID id,
            long revision,
            String title,
            String description,
            String location,
            Instant startsAt,
            Instant endsAt,
            boolean allDay,
            String timeZone,
            String priority,
            String busyStatus,
            String recurrence,
            Integer reminderMinutes,
            List<String> attendees,
            SourceView source,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record SourceView(String kind, UUID mailId, String subject, String sender) {
    }

    /**
     * Тело создания и изменения. {@code revision} обязателен для PUT: без него
     * вторая вкладка молча перезаписала бы более новую версию события.
     */
    public record SaveRequest(
            String title,
            String description,
            String location,
            Instant startsAt,
            Instant endsAt,
            Boolean allDay,
            String timeZone,
            String priority,
            String busyStatus,
            String recurrence,
            Integer reminderMinutes,
            List<String> attendees,
            SourceView source,
            Long revision) {
    }
}
