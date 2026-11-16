package ru.corvex.mailstorage.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.CalendarDtos;
import ru.corvex.mailstorage.calendar.CalendarService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Календарь пользователя. Gateway маршрутизирует /api/v1/calendar/** сюда без
 * срезания префикса. Владелец всегда берётся из JWT subject: идентификатор
 * события сам по себе доступа не даёт.
 */
@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarController {

    private final CalendarService calendar;

    public CalendarController(CalendarService calendar) {
        this.calendar = calendar;
    }

    @GetMapping("/events")
    @PreAuthorize("hasAuthority('CALENDAR_READ')")
    List<CalendarDtos.EventView> events(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        // Строки разбираем сами: конвертация String → Instant в Spring MVC
        // зависит от зарегистрированных форматтеров, и явный разбор даёт
        // предсказуемый 400 вместо 500 при неверной дате.
        return calendar.list(userId(jwt), instant(from, "from"), instant(to, "to"));
    }

    @GetMapping("/events/{id}")
    @PreAuthorize("hasAuthority('CALENDAR_READ')")
    CalendarDtos.EventView event(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return calendar.get(userId(jwt), id);
    }

    @PostMapping("/events")
    @PreAuthorize("hasAuthority('CALENDAR_WRITE')")
    ResponseEntity<CalendarDtos.EventView> create(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestBody CalendarDtos.SaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(calendar.create(userId(jwt), request));
    }

    @PutMapping("/events/{id}")
    @PreAuthorize("hasAuthority('CALENDAR_WRITE')")
    CalendarDtos.EventView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                  @RequestBody CalendarDtos.SaveRequest request) {
        return calendar.update(userId(jwt), id, request);
    }

    /**
     * Удаление требует ревизию: клиент с устаревшим списком не должен стереть
     * событие, которое только что изменили в другом окне.
     */
    @DeleteMapping("/events/{id}")
    @PreAuthorize("hasAuthority('CALENDAR_WRITE')")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @RequestParam(required = false) Long revision) {
        calendar.delete(userId(jwt), id, revision);
        return ResponseEntity.noContent().build();
    }

    private static Instant instant(String value, String parameter) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Параметр " + parameter + " должен быть датой ISO-8601, например 2026-10-19T00:00:00Z");
        }
    }

    private static UUID userId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Отсутствует JWT пользователя");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Некорректный идентификатор пользователя");
        }
    }
}
