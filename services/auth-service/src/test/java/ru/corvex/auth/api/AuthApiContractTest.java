package ru.corvex.auth.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import ru.corvex.auth.api.dto.AuthDtos.AppPasswordView;
import ru.corvex.auth.api.dto.AuthDtos.TokenResponse;
import ru.corvex.auth.api.dto.AuthDtos.UserView;
import ru.corvex.auth.domain.AppPassword;
import ru.corvex.auth.domain.User;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthApiContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final Locale russian = Locale.forLanguageTag("ru");

    @Test
    void apiFactoriesUseExpectedStatuses() {
        assertEquals(HttpStatus.BAD_REQUEST, ApiException.badRequest("x").status());
        assertEquals(HttpStatus.UNAUTHORIZED, ApiException.unauthorized("x").status());
        assertEquals(HttpStatus.FORBIDDEN, ApiException.forbidden("x").status());
        assertEquals(HttpStatus.NOT_FOUND, ApiException.notFound("x").status());
        assertEquals(HttpStatus.CONFLICT, ApiException.conflict("x").status());
        assertEquals(HttpStatus.LOCKED, ApiException.locked("x").status());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ApiException.unprocessable("x").status());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ApiException.serviceUnavailable("x").status());
        assertEquals("x", ApiException.unauthorized("x").getMessage());
    }

    @Test
    void apiExceptionBecomesLocalizedProblemDetail() {
        ResponseEntity<ProblemDetail> response = handler.api(
                ApiException.unauthorized("Неверный логин или пароль"), Locale.ENGLISH);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("Unauthorized", response.getBody().getTitle());
        assertEquals("Incorrect username or password", response.getBody().getDetail());
    }

    @Test
    void dataIntegrityViolationBecomesConflictWithoutSqlText() {
        ResponseEntity<ProblemDetail> response = handler.dataIntegrity(
                new DataIntegrityViolationException("duplicate key value violates unique constraint"),
                Locale.ENGLISH);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("A record with this data already exists", response.getBody().getDetail());
    }

    @Test
    void validationUsesFirstFieldErrorAndLocalizedTitle() {
        MethodArgumentNotValidException exception = validation(List.of(
                new FieldError("login", "username", "", false, null, null, "must not be blank"),
                new FieldError("login", "password", "", false, null, null, "must not be blank")));

        ResponseEntity<ProblemDetail> response = handler.validation(exception, russian);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Ошибка валидации", response.getBody().getTitle());
        assertEquals("username: не должно быть пустым", response.getBody().getDetail());
    }

    @Test
    void validationWithoutFieldErrorsUsesGenericEnglishDetail() {
        ResponseEntity<ProblemDetail> response = handler.validation(validation(List.of()), Locale.ENGLISH);

        assertEquals("Validation failed", response.getBody().getTitle());
        assertEquals("Invalid request", response.getBody().getDetail());
    }

    @Test
    void unreadableJsonBecomesLocalizedBadRequest() {
        ResponseEntity<ProblemDetail> response = handler.unreadable(
                new HttpMessageNotReadableException("broken", mock(HttpInputMessage.class)),
                Locale.ENGLISH);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid request JSON", response.getBody().getDetail());
    }

    @Test
    void dtoFactoriesMapEntitiesAndNormalizeNullAccessLists() {
        UUID userId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        User user = new User();
        user.setId(userId);
        user.setUsername("ivan");
        user.setEmail("ivan@example.test");
        user.setDisplayName("Ivan Petrov");
        user.setSource(User.Source.AD_LDAP);
        user.setStatus(User.Status.BLOCKED);
        user.setDepartment("Sales");
        user.setQuotaBytes(7000L);
        user.setMustChangePassword(true);
        user.setTotpEnabled(true);
        user.setLastLoginAt(Instant.parse("2026-01-02T00:00:00Z"));
        user.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));

        UserView view = UserView.of(user, null, null);
        assertEquals("AD_LDAP", view.source());
        assertEquals("BLOCKED", view.status());
        assertEquals(List.of(), view.groups());
        assertEquals(List.of(), view.permissions());
        assertEquals(0L, view.usedBytes());

        AppPassword appPassword = new AppPassword();
        appPassword.setId(UUID.randomUUID());
        appPassword.setLabel("Thunderbird");
        appPassword.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        appPassword.setLastUsedAt(Instant.parse("2026-01-03T00:00:00Z"));
        AppPasswordView appView = AppPasswordView.of(appPassword);
        assertEquals(appPassword.getId(), appView.id());
        assertEquals("Thunderbird", appView.label());
        assertEquals(new TokenResponse("token", "Bearer", 900), TokenResponse.bearer("token", 900));
    }

    private MethodArgumentNotValidException validation(List<FieldError> errors) {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(errors);
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(bindingResult);
        return exception;
    }
}
