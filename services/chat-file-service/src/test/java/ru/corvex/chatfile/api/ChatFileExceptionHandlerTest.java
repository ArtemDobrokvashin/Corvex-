package ru.corvex.chatfile.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFileExceptionHandlerTest {

    private final ChatFileExceptionHandler handler = new ChatFileExceptionHandler();

    @Test
    void maxUploadSizeMapsToPayloadTooLarge() {
        ProblemDetail problem = handler.tooLarge(new MaxUploadSizeExceededException(1024));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), problem.getStatus());
        assertEquals("Файл превышает допустимый размер", problem.getDetail());
    }

    @Test
    void malformedMultipartMapsToBadRequestWithOriginalReason() {
        ProblemDetail problem = handler.badMultipart(new MultipartException("boundary is missing"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), problem.getStatus());
        assertTrue(problem.getDetail().contains("boundary is missing"));
    }

    @Test
    void domainErrorsKeepTheirReasonAsProblemDetail() {
        // Клиент показывает detail как есть. Без этого обработчика Spring Boot отдаёт
        // /error без message, и вместо «имя занято» пользователь видит общий текст.
        ResponseEntity<ProblemDetail> conflict = handler.status(new ResponseStatusException(
                HttpStatus.CONFLICT, "В этой папке уже есть объект with this name"));
        ResponseEntity<ProblemDetail> tooLarge = handler.status(new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE, "Недостаточно места на диске"));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("В этой папке уже есть объект with this name",
                conflict.getBody().getDetail());
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, tooLarge.getStatusCode());
        assertEquals("Недостаточно места на диске", tooLarge.getBody().getDetail());
    }

    @Test
    void responseStatusWithoutReasonStillHasDetail() {
        ResponseEntity<ProblemDetail> problem = handler.status(
                new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertEquals(HttpStatus.NOT_FOUND, problem.getStatusCode());
        assertTrue(problem.getBody().getDetail() != null
                && !problem.getBody().getDetail().isBlank());
    }

    @Test
    void forbiddenHasReadableDetail() {
        ResponseEntity<ProblemDetail> problem = handler.denied(new AccessDeniedException("nope"));

        assertEquals(HttpStatus.FORBIDDEN, problem.getStatusCode());
        assertEquals("Недостаточно прав для этого действия", problem.getBody().getDetail());
    }

    @Test
    void missingPartAndParameterMapToBadRequest() {
        ProblemDetail missingPart = handler.badMultipart(new MissingServletRequestPartException("file"));
        ProblemDetail missingParameter = handler.badMultipart(
                new MissingServletRequestParameterException("roomId", "UUID"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), missingPart.getStatus());
        assertTrue(missingPart.getDetail().contains("file"));
        assertEquals(HttpStatus.BAD_REQUEST.value(), missingParameter.getStatus());
        assertTrue(missingParameter.getDetail().contains("roomId"));
    }
}
