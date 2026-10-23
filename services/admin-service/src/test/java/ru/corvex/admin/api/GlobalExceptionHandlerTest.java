package ru.corvex.admin.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final Locale russian = Locale.forLanguageTag("ru");

    @Test
    void apiExceptionKeepsStatusAndLocalizesDetail() {
        ResponseEntity<ProblemDetail> response = handler.api(
                ApiException.notFound("Пользователь не существует"), Locale.ENGLISH);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Not Found", response.getBody().getTitle());
        assertEquals("The user does not exist", response.getBody().getDetail());
    }

    @Test
    void apiExceptionFactoriesUseExpectedStatuses() {
        assertEquals(HttpStatus.BAD_REQUEST, ApiException.badRequest("x").status());
        assertEquals(HttpStatus.NOT_FOUND, ApiException.notFound("x").status());
        assertEquals(HttpStatus.CONFLICT, ApiException.conflict("x").status());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ApiException.unprocessable("x").status());
        assertEquals(HttpStatus.BAD_GATEWAY, ApiException.badGateway("x").status());
        assertEquals("x", ApiException.badRequest("x").getMessage());
    }

    @Test
    void accessDeniedNeverLeaksInternalReason() {
        ResponseEntity<ProblemDetail> response = handler.denied(
                new AccessDeniedException("internal policy details"), Locale.ENGLISH);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("Forbidden", response.getBody().getTitle());
        assertEquals("You do not have permission for this action", response.getBody().getDetail());
    }

    @Test
    void dataIntegrityViolationBecomesConflictWithoutSqlDetails() {
        ResponseEntity<ProblemDetail> response = handler.dataIntegrity(
                new DataIntegrityViolationException("duplicate key value violates unique constraint"),
                Locale.ENGLISH);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("A record with this data already exists", response.getBody().getDetail());
    }

    @Test
    void validationUsesFirstFieldErrorAndLocalizesIt() {
        MethodArgumentNotValidException exception = validation(List.of(
                new FieldError("request", "quotaBytes", -1L, false, null, null,
                        "must be greater than or equal to 0"),
                new FieldError("request", "email", "bad", false, null, null,
                        "must be a well-formed email address")));

        ResponseEntity<ProblemDetail> response = handler.validation(exception, russian);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Ошибка валидации", response.getBody().getTitle());
        assertEquals("quotaBytes: должно быть не меньше 0", response.getBody().getDetail());
    }

    @Test
    void validationWithoutFieldErrorsUsesGenericDetail() {
        ResponseEntity<ProblemDetail> response = handler.validation(
                validation(List.of()), Locale.ENGLISH);

        assertEquals("Validation failed", response.getBody().getTitle());
        assertEquals("Invalid request", response.getBody().getDetail());
    }

    @Test
    void unreadableJsonBecomesBadRequest() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "broken", mock(HttpInputMessage.class));

        ResponseEntity<ProblemDetail> response = handler.unreadable(exception, Locale.ENGLISH);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid request JSON", response.getBody().getDetail());
    }

    private MethodArgumentNotValidException validation(List<FieldError> errors) {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(errors);
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(bindingResult);
        return exception;
    }
}
