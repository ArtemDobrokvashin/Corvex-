package ru.corvex.mailstorage.calendar;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.CalendarDtos;
import ru.corvex.mailstorage.domain.CalendarEvent;
import ru.corvex.mailstorage.repository.CalendarEventRepository;
import ru.corvex.mailstorage.repository.CalendarReminderRepository;
import ru.corvex.mailstorage.repository.MessageMetadataRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Календарь владельца: чтение и изменение только своих событий.
 *
 * <p>Валидация намеренно строгая и объяснимая: клиент получает 400 с причиной,
 * а не «тихо исправленное» событие. Все проверки продублированы ограничениями
 * БД, чтобы обойти их через другой клиент было нельзя.
 */
@Service
public class CalendarService {

    static final int MAX_TITLE = 200;
    static final int MAX_LOCATION = 200;
    static final int MAX_DESCRIPTION = 20_000;
    static final int MAX_ATTENDEES = 50;
    static final int MAX_ATTENDEE_LENGTH = 320;
    static final int MAX_REMINDER_MINUTES = 10_080;
    static final int MAX_RANGE_DAYS = 400;
    private static final Set<String> PRIORITIES = Set.of("high", "medium", "low");
    private static final Set<String> BUSY = Set.of("busy", "tentative", "free");
    private static final Set<String> SOURCES = Set.of("NONE", "MAIL", "CHAT");

    private final CalendarEventRepository events;
    private final CalendarReminderRepository reminders;
    private final MessageMetadataRepository messages;

    public CalendarService(CalendarEventRepository events, CalendarReminderRepository reminders,
                           MessageMetadataRepository messages) {
        this.events = events;
        this.reminders = reminders;
        this.messages = messages;
    }

    @Transactional(readOnly = true)
    public List<CalendarDtos.EventView> list(UUID ownerId, Instant from, Instant to) {
        Instant end = to == null ? Instant.now().plus(Duration.ofDays(62)) : to;
        Instant start = from == null ? end.minus(Duration.ofDays(62)) : from;
        if (!start.isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Начало диапазона должно быть раньше конца");
        }
        if (Duration.between(start, end).toDays() > MAX_RANGE_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Слишком большой диапазон дат");
        }
        return events.findVisible(ownerId, start, end).stream().map(CalendarService::toView).toList();
    }

    @Transactional(readOnly = true)
    public CalendarDtos.EventView get(UUID ownerId, UUID id) {
        return toView(load(ownerId, id));
    }

    @Transactional
    public CalendarDtos.EventView create(UUID ownerId, CalendarDtos.SaveRequest request) {
        CalendarEvent event = new CalendarEvent();
        event.setOwnerId(ownerId);
        apply(event, request, ownerId);
        event.setRevision(1);
        return toView(events.saveAndFlush(event));
    }

    @Transactional
    public CalendarDtos.EventView update(UUID ownerId, UUID id, CalendarDtos.SaveRequest request) {
        CalendarEvent event = load(ownerId, id);
        if (request.revision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не указана ревизия события");
        }
        if (request.revision() != event.getRevision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Событие изменено в другом окне; обновите список перед сохранением");
        }
        apply(event, request, ownerId);
        event.setRevision(event.getRevision() + 1);
        return toView(events.saveAndFlush(event));
    }

    /** Удаление мягкое и требует ревизию: устаревший клиент не удалит чужую правку. */
    @Transactional
    public void delete(UUID ownerId, UUID id, Long revision) {
        CalendarEvent event = load(ownerId, id);
        if (revision != null && revision != event.getRevision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Событие изменено в другом окне; обновите список перед удалением");
        }
        event.setDeletedAt(Instant.now());
        event.setRevision(event.getRevision() + 1);
        events.saveAndFlush(event);
        // Удалённое событие не должно прислать напоминание позже.
        reminders.cancelPendingForEvent(id, "Событие удалено");
    }

    private CalendarEvent load(UUID ownerId, UUID id) {
        return events.findByIdAndOwnerIdAndDeletedAtIsNull(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Событие не найдено"));
    }

    private void apply(CalendarEvent event, CalendarDtos.SaveRequest request, UUID ownerId) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Событие не задано");
        }
        String title = trim(request.title());
        if (title.isEmpty() || title.length() > MAX_TITLE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Тема события обязательна и не длиннее 200 символов");
        }
        if (request.startsAt() == null || request.endsAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите начало и конец события");
        }
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Конец события должен быть позже начала");
        }
        String zone = request.timeZone() == null || request.timeZone().isBlank() ? "UTC" : request.timeZone().trim();
        try {
            ZoneId.of(zone);
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный часовой пояс");
        }
        String priority = request.priority() == null ? "medium" : request.priority();
        if (!PRIORITIES.contains(priority)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимый приоритет события");
        }
        String busy = request.busyStatus() == null ? "busy" : request.busyStatus();
        if (!BUSY.contains(busy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимый статус занятости");
        }
        String recurrence = request.recurrence() == null ? "none" : request.recurrence();
        if (!CalendarRecurrence.RULES.contains(recurrence)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимое повторение события");
        }
        Integer reminder = request.reminderMinutes();
        if (reminder != null && (reminder < 0 || reminder > MAX_REMINDER_MINUTES)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Напоминание задаётся от 0 до 10080 минут (7 дней)");
        }
        String description = request.description() == null ? "" : request.description();
        if (description.length() > MAX_DESCRIPTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Описание события слишком длинное");
        }
        String location = request.location() == null ? "" : trim(request.location());
        if (location.length() > MAX_LOCATION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Место события слишком длинное");
        }

        CalendarDtos.SourceView source = request.source() == null
                ? new CalendarDtos.SourceView("NONE", null, null, null)
                : request.source();
        String sourceKind = source.kind() == null ? "NONE" : source.kind().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(sourceKind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимый источник события");
        }
        if ("MAIL".equals(sourceKind)) {
            if (source.mailId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для события из письма нужен идентификатор письма");
            }
            // Чужое письмо нельзя привязать к событию: это утечка темы и отправителя.
            if (messages.findByIdAndOwnerId(source.mailId(), ownerId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Письмо-источник не найдено");
            }
        }

        event.setTitle(title);
        event.setDescription(description);
        event.setLocation(location);
        event.setStartsAt(request.startsAt());
        event.setEndsAt(request.endsAt());
        event.setAllDay(Boolean.TRUE.equals(request.allDay()));
        event.setTimeZone(zone);
        event.setPriority(priority);
        event.setBusyStatus(busy);
        event.setRecurrence(recurrence);
        event.setReminderMinutes(reminder);
        event.setAttendees(String.join("\n", normalizeAttendees(request.attendees())));
        event.setSourceKind(sourceKind);
        event.setSourceMailId("MAIL".equals(sourceKind) ? source.mailId() : null);
        event.setSourceMailSubject("MAIL".equals(sourceKind) ? truncate(source.subject(), 512) : null);
        event.setSourceMailSender("MAIL".equals(sourceKind) ? truncate(source.sender(), 512) : null);
    }

    /** Участники: адреса или ФИО, без управляющих символов, без дублей, не больше 50. */
    static List<String> normalizeAttendees(List<String> attendees) {
        if (attendees == null || attendees.isEmpty()) {
            return List.of();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String raw : attendees) {
            if (raw == null) continue;
            String value = raw.trim();
            if (value.isEmpty()) continue;
            if (value.length() > MAX_ATTENDEE_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Слишком длинный участник события");
            }
            if (value.chars().anyMatch(c -> c < 32 || c == 127)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Участник события содержит недопустимые символы");
            }
            unique.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
        }
        if (unique.size() > MAX_ATTENDEES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не больше 50 участников события");
        }
        return new ArrayList<>(unique.values());
    }

    static CalendarDtos.EventView toView(CalendarEvent event) {
        List<String> attendees = event.getAttendees() == null || event.getAttendees().isBlank()
                ? List.of()
                : List.of(event.getAttendees().split("\n"));
        CalendarDtos.SourceView source = new CalendarDtos.SourceView(
                event.getSourceKind(), event.getSourceMailId(),
                event.getSourceMailSubject(), event.getSourceMailSender());
        return new CalendarDtos.EventView(
                event.getId(), event.getRevision(), event.getTitle(), event.getDescription(), event.getLocation(),
                event.getStartsAt(), event.getEndsAt(), event.isAllDay(), event.getTimeZone(),
                event.getPriority(), event.getBusyStatus(), event.getRecurrence(), event.getReminderMinutes(),
                attendees, source, event.getCreatedAt(), event.getUpdatedAt());
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Часовой пояс по умолчанию, если клиент не прислал зону. */
    static ZoneId zoneOrDefault(String timeZone) {
        return timeZone == null || timeZone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timeZone);
    }
}
