package ru.corvex.admin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.corvex.admin.api.dto.AdminDtos.AdminUserView;
import ru.corvex.admin.api.dto.AdminDtos.CreateUserRequest;
import ru.corvex.admin.api.dto.AdminDtos.PageResult;
import ru.corvex.admin.api.dto.AdminDtos.PatchUserRequest;
import ru.corvex.admin.client.AuthServiceClient;
import ru.corvex.admin.client.AuthServiceClient.RemotePage;
import ru.corvex.admin.client.AuthServiceClient.RemoteUser;
import ru.corvex.admin.domain.UserGroup;
import ru.corvex.admin.service.AuditService.Actor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Оркестрация управления пользователями: учётка создаётся в auth-service (владелец
 * auth_db), членство в группах пишется здесь, всё фиксируется в аудите. При сбое
 * после создания учётки выполняется компенсация — учётка отключается.
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final AuthServiceClient authClient;
    private final AdminGroupService groupService;
    private final AuditService audit;

    public AdminUserService(AuthServiceClient authClient, AdminGroupService groupService, AuditService audit) {
        this.authClient = authClient;
        this.groupService = groupService;
        this.audit = audit;
    }

    public AdminUserView create(CreateUserRequest req, Actor actor) {
        List<UserGroup> groupsToAssign = groupService.resolveGroups(
                req.groupCodes() == null || req.groupCodes().isEmpty() ? List.of("USER") : req.groupCodes());
        RemoteUser created = authClient.createUser(
                req.username(), req.email(), req.displayName(), req.tempPassword(),
                req.department(), req.quotaBytes());
        try {
            groupService.replaceUserGroups(created.id(), groupsToAssign);
        } catch (RuntimeException e) {
            log.error("Group assignment failed for new user {} — compensating by disabling the account", created.id(), e);
            try {
                authClient.disableUser(created.id());
            } catch (RuntimeException compensationError) {
                log.error("Compensation (disable) also failed for user {}", created.id(), compensationError);
            }
            throw e;
        }
        audit.record(actor, "USER_CREATED", "user", created.id().toString(),
                Map.of("username", created.username(), "email", created.email(),
                        "groups", groupsToAssign.stream().map(UserGroup::getCode).toList()));
        return toView(created, groupsToAssign.stream().map(UserGroup::getCode).toList());
    }

    public PageResult<AdminUserView> list(String q, String status, String department, int page, int size) {
        RemotePage remote = authClient.listUsers(q, status, department, page, size);
        Map<UUID, List<String>> groupsByUser = groupService.groupCodesByUserIds(
                remote.content().stream().map(RemoteUser::id).toList());
        List<AdminUserView> content = remote.content().stream()
                .map(u -> toView(u, groupsByUser.getOrDefault(u.id(), List.of())))
                .toList();
        return new PageResult<>(content, remote.page(), remote.size(), remote.totalElements(), remote.totalPages());
    }

    public AdminUserView patch(UUID userId, PatchUserRequest req, Actor actor) {
        Map<String, Object> patch = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();
        if (req.displayName() != null) { patch.put("displayName", req.displayName()); changed.add("displayName"); }
        if (req.email() != null) { patch.put("email", req.email()); changed.add("email"); }
        if (req.department() != null) { patch.put("department", req.department()); changed.add("department"); }
        if (req.quotaBytes() != null) { patch.put("quotaBytes", req.quotaBytes()); changed.add("quotaBytes"); }
        if (req.status() != null) {
            if (!"ACTIVE".equalsIgnoreCase(req.status().trim())) {
                groupService.assertNotSoleSuperAdmin(userId);
            }
            patch.put("status", req.status());
            changed.add("status");
        }
        RemoteUser updated = authClient.patchUser(userId, patch);
        audit.record(actor, "USER_UPDATED", "user", userId.toString(), Map.of("changedFields", changed));
        return toView(updated, groupService.groupCodesOf(userId));
    }

    public void block(UUID userId, Actor actor) {
        groupService.assertNotSoleSuperAdmin(userId);
        authClient.blockUser(userId); // auth попутно отзывает все refresh-сессии
        audit.record(actor, "USER_BLOCKED", "user", userId.toString(), Map.of());
    }

    public void disable(UUID userId, Actor actor) {
        groupService.assertNotSoleSuperAdmin(userId);
        authClient.disableUser(userId);
        groupService.removeUserMemberships(userId);
        audit.record(actor, "USER_DISABLED", "user", userId.toString(), Map.of());
    }

    public AdminUserView setGroups(UUID userId, List<String> groupCodes, Actor actor) {
        RemoteUser user = authClient.getUser(userId); // 404 пробросится наружу
        List<UserGroup> resolved = groupService.resolveGroups(groupCodes);
        groupService.replaceUserGroups(userId, resolved);
        List<String> codes = resolved.stream().map(UserGroup::getCode).toList();
        audit.record(actor, "USER_GROUPS_CHANGED", "user", userId.toString(), Map.of("groups", codes));
        return toView(user, codes);
    }

    private static AdminUserView toView(RemoteUser u, List<String> group) {
        return new AdminUserView(
                u.id(), u.username(), u.email(), u.displayName(), u.source(), u.status(),
                u.department(), group, u.quotaBytes(), u.usedBytes(),
                u.mustChangePassword(), u.totpEnabled(), u.lastLoginAt(), u.createdAt());
    }
}
