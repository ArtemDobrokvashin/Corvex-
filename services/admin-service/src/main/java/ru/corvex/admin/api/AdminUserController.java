package ru.corvex.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.admin.api.dto.AdminDtos.AdminUserView;
import ru.corvex.admin.api.dto.AdminDtos.CreateUserRequest;
import ru.corvex.admin.api.dto.AdminDtos.PageResult;
import ru.corvex.admin.api.dto.AdminDtos.PatchUserRequest;
import ru.corvex.admin.api.dto.AdminDtos.SetGroupsRequest;
import ru.corvex.admin.service.AdminUserService;

import java.util.UUID;

/** Пользователи в админке — зеркало docs/api/admin-api.yaml. */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService users;

    public AdminUserController(AdminUserService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN_USERS_MANAGE')")
    PageResult<AdminUserView> list(@RequestParam(required = false) String q,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String department,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        return users.list(q, status, department, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ADMIN_USERS_MANAGE')")
    AdminUserView create(@Valid @RequestBody CreateUserRequest req,
                         @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return users.create(req, Actors.from(jwt, http));
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasAnyAuthority('ADMIN_USERS_MANAGE','ADMIN_QUOTAS_MANAGE')")
    AdminUserView patch(@PathVariable UUID userId, @Valid @RequestBody PatchUserRequest req,
                        @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return users.patch(userId, req, Actors.from(jwt, http));
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('ADMIN_USERS_MANAGE')")
    void disable(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        users.disable(userId, Actors.from(jwt, http));
    }

    @PostMapping("/{userId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('ADMIN_USERS_MANAGE')")
    void block(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        users.block(userId, Actors.from(jwt, http));
    }

    @PutMapping("/{userId}/groups")
    @PreAuthorize("hasAnyAuthority('ADMIN_GROUPS_MANAGE','ADMIN_USERS_MANAGE')")
    AdminUserView setGroups(@PathVariable UUID userId, @Valid @RequestBody SetGroupsRequest req,
                            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return users.setGroups(userId, req.groupCodes(), Actors.from(jwt, http));
    }
}
