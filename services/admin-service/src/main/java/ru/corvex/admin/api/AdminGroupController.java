package ru.corvex.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.admin.api.dto.AdminDtos.CreateGroupRequest;
import ru.corvex.admin.api.dto.AdminDtos.GroupView;
import ru.corvex.admin.api.dto.AdminDtos.SetPermissionsRequest;
import ru.corvex.admin.service.AdminGroupService;

import java.util.List;

/** Группы и чекбоксы прав в админке. */
@RestController
@RequestMapping("/api/v1/admin/groups")
public class AdminGroupController {

    private final AdminGroupService groups;

    public AdminGroupController(AdminGroupService groups) {
        this.groups = groups;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ADMIN_GROUPS_MANAGE','ADMIN_USERS_MANAGE')")
    List<GroupView> list() {
        return groups.listGroups();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ADMIN_GROUPS_MANAGE')")
    GroupView create(@Valid @RequestBody CreateGroupRequest req,
                     @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return groups.createGroup(req, Actors.from(jwt, http));
    }

    @PutMapping("/{groupCode}/permissions")
    @PreAuthorize("hasAuthority('ADMIN_GROUPS_MANAGE')")
    GroupView setPermissions(@PathVariable String groupCode, @Valid @RequestBody SetPermissionsRequest req,
                             @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return groups.setPermissions(groupCode, req.permissions(), Actors.from(jwt, http));
    }
}
