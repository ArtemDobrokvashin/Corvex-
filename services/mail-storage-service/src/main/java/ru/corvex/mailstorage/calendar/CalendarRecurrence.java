package ru.corvex.mailstorage.calendar;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Разворачивание повторяющихся событий по локальному времени зоны события.
 *
 * <p>Важно не считать «каждые 24 часа от первого старта»: при переходе на летнее
 * время такое событие уехало бы на час. Вхождения строятся из локальной даты и
 * локального времени старта в {@code ZoneId} события.
 */
public final class CalendarRecurrence {

    public static final Set<String> RULES = Set.of("none", "daily", "weekdays", "weekly", "monthly");

    private CalendarRecurrence() {
    }

    /** Начала вхождений в полуинтервале [{@code from}, {@code to}). */
    public static List<Instant> occurrences(String rule, Instant firstStart, String timeZone,
                                            Instant from, Instant to) {
        if (firstStart == null || to == null) {
            return List.of();
        }
        if (rule == null || "none".equals(rule)) {
            boolean visible = !firstStart.isBefore(from == null ? Instant.MIN : from) && firstStart.isBefore(to);
            return visible ? List.of(firstStart) : List.of();
        }
        ZoneId zone = ZoneId.of(timeZone);
        ZonedDateTime firstLocal = firstStart.atZone(zone);
        LocalTime timeOfDay = firstLocal.toLocalTime();
        LocalDate startDate = firstLocal.toLocalDate();
        LocalDate firstCandidate = (from == null ? startDate : from.atZone(zone).toLocalDate()).isBefore(startDate)
                ? startDate
                : from.atZone(zone).toLocalDate();
        LocalDate lastCandidate = to.atZone(zone).toLocalDate().plusDays(1);

        List<Instant> result = new ArrayList<>();
        for (LocalDate date = firstCandidate; !date.isAfter(lastCandidate); date = date.plusDays(1)) {
            if (!matches(rule, startDate, date)) {
                continue;
            }
            Instant start = ZonedDateTime.of(date, timeOfDay, zone).toInstant();
            if (start.isBefore(from) || !start.isBefore(to)) {
                continue;
            }
            result.add(start);
        }
        return result;
    }

    static boolean matches(String rule, LocalDate startDate, LocalDate date) {
        return switch (rule) {
            case "daily" -> true;
            case "weekdays" -> date.getDayOfWeek().getValue() <= 5;
            case "weekly" -> date.getDayOfWeek() == startDate.getDayOfWeek();
            // 31-е число пропускает месяцы, где его нет: переносить событие на 1-е
            // число следующего месяца значило бы менять смысл встречи без ведома владельца.
            case "monthly" -> date.getDayOfMonth() == startDate.getDayOfMonth();
            default -> false;
        };
    }

    /** Длительность события сохраняется при каждом вхождении. */
    public static Duration duration(Instant startsAt, Instant endsAt) {
        return Duration.between(startsAt, endsAt);
    }
}
