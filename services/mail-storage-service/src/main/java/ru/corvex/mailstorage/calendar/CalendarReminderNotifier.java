package ru.corvex.mailstorage.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.corvex.mailstorage.security.InternalTokenGuard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Отправка напоминания в notification-service, который владеет SMTP-каналом
 * служебных писем. Ошибка доставки не проглатывается: планировщик повторит
 * попытку, а после исчерпания попыток пометит доставку FAILED.
 */
@Component
public class CalendarReminderNotifier {

    private static final Logger log = LoggerFactory.getLogger(CalendarReminderNotifier.class);

    private final String baseUrl;
    private final InternalTokenGuard tokens;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public CalendarReminderNotifier(
            @Value("${corvex.calendar.notification-base-url:${CORVEX_NOTIFICATION_SERVICE_URL:http://notification-service:8091}}")
            String baseUrl,
            InternalTokenGuard tokens,
            ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.tokens = tokens;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public record Reminder(String to, String title, String startsAt, String timeZone,
                           String location, int attendeeCount, boolean allDay) {
    }

    public void send(Reminder reminder) throws Exception {
        String body = mapper.writeValueAsString(reminder);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/calendar/reminders"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header(InternalTokenGuard.HEADER, tokens.tokenOrFail())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("notification-service ответил " + response.statusCode());
        }
        log.info("Calendar reminder queued for delivery");
    }
}
