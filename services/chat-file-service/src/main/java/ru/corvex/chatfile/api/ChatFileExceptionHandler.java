package ru.corvex.chatfile.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Единый RFC 7807 для ошибок сервиса.
 *
 * <p>Без обработчика {@link ResponseStatusException} уходил в стандартный /error
 * Spring Boot, а тот по умолчанию не отдаёт текст причины ({@code server.error.include-message=never}).
 * Клиент получал 409/413/422/423/503 без {@code detail} и показывал общее сообщение вместо
 * причины: «имя занято», «файл отклонён антивирусом», «недостаточно места». Поэтому здесь
 * явно возвращается {@code detail} из reason.</p>
 */
@RestControllerAdvice
public class ChatFileExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Файл превышает допустимый размер");
    }

    @ExceptionHandler({
            MultipartException.class,
            MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class
    })
    ProblemDetail badMultipart(Exception e) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Некорректный multipart-запрос: " + e.getMessage());
    }

    /**
     * Доменные ошибки сервиса: 409 «имя занято», 413 квота, 422 вирус, 423 нет AV-статуса,
     * 503 антивирус недоступен, 404 объекта нет. Reason уже человекочитаемый, поэтому
     * отдаём его как detail без переупаковки.
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

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Недостаточно прав для этого действия");
        problem.setTitle(HttpStatus.FORBIDDEN.getReasonPhrase());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }
}
