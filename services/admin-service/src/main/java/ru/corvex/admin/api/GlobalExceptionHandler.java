package ru.corvex.admin.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Locale;

/** Единый формат ошибок API — RFC 7807 Problem Details (соглашение docs/03). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e, Locale locale) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                e.status(), ApiMessageLocalizer.localize(e.getMessage(), locale));
        pd.setTitle(e.status().getReasonPhrase());
        return new ResponseEntity<>(pd, e.status());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e, Locale locale) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                ApiMessageLocalizer.localize("Недостаточно прав для этого действия", locale));
        pd.setTitle("Forbidden");
        return new ResponseEntity<>(pd, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> dataIntegrity(DataIntegrityViolationException e, Locale locale) {
        // Гонка двух одинаковых create-запросов может пройти предварительный exists-check
        // и упереться уже в UNIQUE/FK в БД. Для клиента это конфликт данных, а не 500;
        // SQL-текст наружу не отдаём.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                ApiMessageLocalizer.localize("Запись с такими данными уже существует", locale));
        pd.setTitle(HttpStatus.CONFLICT.getReasonPhrase());
        return new ResponseEntity<>(pd, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e, Locale locale) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("Некорректный запрос");
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ApiMessageLocalizer.localize(detail, locale));
        pd.setTitle(ApiMessageLocalizer.isEnglish(locale) ? "Validation failed" : "Ошибка валидации");
        return new ResponseEntity<>(pd, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e, Locale locale) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                ApiMessageLocalizer.localize("Некорректный JSON запроса", locale));
        pd.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        return new ResponseEntity<>(pd, HttpStatus.BAD_REQUEST);
    }
}
