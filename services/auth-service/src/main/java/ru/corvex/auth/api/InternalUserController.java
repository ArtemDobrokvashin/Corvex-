package ru.corvex.auth.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.auth.api.dto.AuthDtos.CreateUserRequest;
import ru.corvex.auth.api.dto.AuthDtos.PatchUserRequest;
import ru.corvex.auth.api.dto.AuthDtos.StatsResponse;
import ru.corvex.auth.api.dto.AuthDtos.UserPage;
import ru.corvex.auth.api.dto.AuthDtos.UserView;
import ru.corvex.auth.api.dto.AuthDtos.VerifyPasswordRequest;
import ru.corvex.auth.api.dto.AuthDtos.VerifyPasswordResponse;
import ru.corvex.auth.domain.User;
import ru.corvex.auth.service.InternalUserService;

import java.util.UUID;

/**
 * Внутренний API для admin-service и будущих почтовых протоколов (IMAP/SMTP).
 * Gateway маршрутизирует только /api/v1/**, а InternalApiTokenFilter дополнительно
 * требует X-Corvex-Internal-Token. mTLS остаётся дальнейшим усилением этапа 5.
 */
@RestController
@RequestMapping("/internal")
public class InternalUserController {

    private final InternalUserService users;

    public InternalUserController(InternalUserService users) {
        this.users = users;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    UserView create(@Valid @RequestBody CreateUserRequest req) {
        return users.create(req);
    }

    @GetMapping("/users/{id}")
    UserView get(@PathVariable UUID id) {
        return users.get(id);
    }

    @GetMapping("/users/by-username/{username}")
    UserView byUsername(@PathVariable String username) {
        return users.getByUsername(username);
    }

    @GetMapping("/users/by-email")
    UserView byEmail(@RequestParam String email) {
        return users.getByEmail(email);
    }

    @GetMapping("/users")
    UserPage list(@RequestParam(required = false) String q,
                  @RequestParam(required = false) String status,
                  @RequestParam(required = false) String department,
                  @RequestParam(defaultValue = "0") int page,
                  @RequestParam(defaultValue = "50") int size) {
        return users.list(q, status, department, page, size);
    }

    @PatchMapping("/users/{id}")
    UserView patch(@PathVariable UUID id, @Valid @RequestBody PatchUserRequest req) {
        return users.patch(id, req);
    }

    @PostMapping("/users/{id}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void block(@PathVariable UUID id) {
        users.setStatus(id, User.Status.BLOCKED);
    }

    @PostMapping("/users/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disable(@PathVariable UUID id) {
        users.setStatus(id, User.Status.DISABLED);
    }

    @PostMapping("/verify-password")
    VerifyPasswordResponse verifyPassword(@Valid @RequestBody VerifyPasswordRequest req) {
        return users.verifyPassword(req.username(), req.password());
    }

    @GetMapping("/stats")
    StatsResponse stats(@RequestParam(required = false) String department) {
        return users.stats(department);
    }
}
