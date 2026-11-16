package ru.corvex.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Напоминания календаря по почте.
 *
 * <p>Календарь и его очередь напоминаний живут в mail-storage-service; этот
 * сервис владеет SMTP-каналом служебных писем, поэтому отправка идёт здесь.
 * Ошибку SMTP возвращаем как 503, чтобы планировщик повторил доставку —
 * «письмо не отправлено, но напоминание потеряно» недопустимо.
 */
@RestController
public class CalendarReminderController {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru"));

    private final JavaMailSender mailSender;
    private final InternalTokenGuard tokens;
    private final String fromAddress;

    public CalendarReminderController(JavaMailSender mailSender,
                                      InternalTokenGuard tokens,
                                      @Value("${corvex.calendar.from:${CORVEX_CALENDAR_FROM:corvex-calendar@company.local}}")
                                      String fromAddress) {
        this.mailSender = mailSender;
        this.tokens = tokens;
        this.fromAddress = fromAddress;
    }

    public record ReminderRequest(String to, String title, String startsAt, String timeZone,
                                  String location, Integer attendeeCount, Boolean allDay) {
    }

    @PostMapping("/internal/calendar/reminders")
    ResponseEntity<Void> reminder(@RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
                                  @RequestBody ReminderRequest request) {
        tokens.verify(token);
        if (request == null || request.to() == null || !request.to().matches("[^\\s@,;]+@[^\\s@,;]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный получатель напоминания");
        }
        String title = request.title() == null ? "" : request.title().trim();
        if (title.isEmpty() || title.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная тема напоминания");
        }
        Instant start;
        try {
            start = Instant.parse(request.startsAt());
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректное время события");
        }
        ZoneId zone;
        try {
            zone = request.timeZone() == null || request.timeZone().isBlank()
                    ? ZoneId.of("UTC") : ZoneId.of(request.timeZone());
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный часовой пояс");
        }

        String when = DATE_TIME.format(start.atZone(zone)) + " (" + zone.getId() + ")";
        // Напоминание может прийти с задержкой после простоя сервера: не пишем
        // «скоро», если событие уже началось.
        boolean started = start.isBefore(Instant.now());
        String heading = Boolean.TRUE.equals(request.allDay())
                ? "Сегодня событие на весь день"
                : started ? "Событие уже началось" : "Скоро событие";
        StringBuilder text = new StringBuilder()
                .append(heading)
                .append(": ").append(title).append('\n')
                .append("Начало: ").append(when).append('\n');
        if (request.location() != null && !request.location().isBlank()) {
            text.append("Место: ").append(request.location()).append('\n');
        }
        if (request.attendeeCount() != null && request.attendeeCount() > 0) {
            text.append("Участников: ").append(request.attendeeCount()).append('\n');
        }
        text.append("\nЭто напоминание отправлено сервером Corvex; браузер может быть закрыт.");

        var message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(request.to());
        message.setSubject("[Corvex] Напоминание: " + title);
        message.setText(text.toString());
        try {
            mailSender.send(message);
        } catch (MailException failure) {
            // 503, а не 200: планировщик оставит напоминание в очереди и повторит.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Не удалось отправить напоминание: " + failure.getMessage());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
