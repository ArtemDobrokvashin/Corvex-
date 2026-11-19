package ru.corvex.mailstorage.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Единый RFC 7807 для ошибок сервиса почты.
 *
 * <p>Без обработчика {@link ResponseStatusException} уходил в стандартный /error Spring Boot,
 * а тот по умолчанию не отдаёт текст причины ({@code server.error.include-message=never}).
 * Клиент получал 409 без {@code detail} и показывал «HTTP 409 /api/...» вместо причины.
 * На стенде это скрыло главный текст OAuth-отказа: «Согласие Google/Microsoft отозвано или
 * истекло: подключите ящик заново через кнопку входа».</p>
 */
@RestControllerAdvice
public class MailStorageExceptionHandler {

    /**
     * Доменные ошибки сервиса: 409 «ящик уже подключён» или отозванное согласие, 404 «ящик
     * не найден», 503 «внешняя почта выключена». Reason уже человекочитаемый, поэтому отдаём
     * его как detail без переупаковки.
     */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> status(ResponseStatusException e) {
        HttpStatusCode code = e.getStatusCode();
        String detail = e.getReason() == null || e.getReason().isBlank()
                ? code.toString()
                : e.getReason();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code, detail);
        HttpStatus reason = HttpStatus.resolve(code.value());
        problem.setTitle(reason != null ? reason.getReasonPhrase() : code.toString());
        return ResponseEntity.status(code).body(problem);
    }
}
