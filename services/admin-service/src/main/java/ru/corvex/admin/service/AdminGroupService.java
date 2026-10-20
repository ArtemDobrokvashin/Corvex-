package ru.corvex.admin.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.corvex.admin.api.ApiException;
import ru.corvex.admin.api.dto.AdminDtos.AccessView;
import ru.corvex.admin.api.dto.AdminDtos.CreateGroupRequest;
import ru.corvex.admin.api.dto.AdminDtos.GroupView;
import ru.corvex.admin.domain.UserGroup;
import ru.corvex.admin.rbac.EffectivePermissionService;
import ru.corvex.admin.rbac.Permission;
import ru.corvex.admin.repository.UserGroupRepository;
import ru.corvex.admin.service.AuditService.Actor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Группы, чекбоксы прав и членство пользователей. Эффективные права — union по всем
 * включённым группам; членство в SUPER_ADMIN означает весь каталог прав безусловно
 * (в БД у SUPER_ADMIN строк прав нет — защита от самоблокировки чекбоксами).
 */
@Service
public class AdminGroupService {

    private final UserGroupRepository groups;
    private final JdbcTemplate jdbc;
    private final EffectivePermissionService effectivePermissions;
    private final AuditService audit;

    public AdminGroupService(UserGroupRepository groups, JdbcTemplate jdbc,
                             EffectivePermissionService effectivePermissions, AuditService audit) {
        this.groups = groups;
        this.jdbc = jdbc;
        this.effectivePermissions = effectivePermissions;
        this.audit = audit;
    }

    // ---------- Группы и права ----------

    @Transactional(readOnly = true)
    public List<GroupView> listGroups() {
        Map<UUID, Long> counts = memberCounts();
        return groups.findAllByOrderByCodeAsc().stream()
                .map(g -> view(g, counts.getOrDefault(g.getId(), 0L)))
                .toList();
    }

    @Transactional
    public GroupView createGroup(CreateGroupRequest req, Actor actor) {
        String code = normalizeCode(req.code());
        if (groups.existsByCode(code)) {
            throw ApiException.conflict("Группа уже существует: " + code);
        }
        UserGroup g = new UserGroup();
        g.setCode(code);
        g.setName(req.name().trim());
        g.setSystem(false);
        g.setEnabled(true);
        g.setPermissions(parsePermissions(req.permissions()));
        UserGroup saved = groups.save(g);
        audit.record(actor, "GROUP_CREATED", "group", code,
                Map.of("name", saved.getName(), "permissions", permissionNames(saved.getPermissions())));
        return view(saved, 0);
    }

    @Transactional
    public GroupView setPermissions(String codeRaw, List<String> permissionCodes, Actor actor) {
        UserGroup g = loadByCode(normalizeCode(codeRaw));
        if (g.isSuperAdmin()) {
            throw ApiException.unprocessable("Права группы SUPER_ADMIN не редактируются (страховка от самоблокировки)");
        }
        Set<Permission> next = parsePermissions(permissionCodes);
        Set<Permission> before = EnumSet.noneOf(Permission.class);
        before.addAll(g.getPermissions());
        g.getPermissions().clear();
        g.getPermissions().addAll(next);
        groups.save(g);

        Set<String> added = new LinkedHashSet<>(permissionNames(next));
        added.removeAll(permissionNames(before));
        Set<String> removed = new LinkedHashSet<>(permissionNames(before));
        removed.removeAll(permissionNames(next));
        audit.record(actor, "GROUP_PERMISSIONS_CHANGED", "group", g.getCode(),
                Map.of("added", List.copyOf(added), "removed", List.copyOf(removed)));
        return view(g, memberCounts().getOrDefault(g.getId(), 0L));
    }

    private GroupView view(UserGroup g, long memberCount) {
        List<String> perms = g.isSuperAdmin()
                ? permissionNames(EnumSet.allOf(Permission.class))
                : permissionNames(g.getPermissions());
        return new GroupView(g.getCode(), g.getName(), g.isSystem(), g.isEnabled(), perms, memberCount);
    }

    // ---------- Членство (JDBC: user_id — логическая ссылка в auth_db) ----------

    @Transactional(readOnly = true)
    public List<UserGroup> resolveGroups(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        List<UserGroup> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : codes) {
            String code = normalizeCode(raw);
            if (seen.add(code)) {
                result.add(loadByCode(code));
            }
        }
        return result;
    }

    @Transactional
    public void replaceUserGroups(UUID userId, Collection<UserGroup> newGroups) {
        boolean keepsSuperAdmin = newGroups.stream().anyMatch(UserGroup::isSuperAdmin);
        if (!keepsSuperAdmin) {
            assertNotSoleSuperAdmin(userId);
        }
        jdbc.update("DELETE FROM user_group_members WHERE user_id = ?", userId);
        for (UserGroup g : newGroups) {
            jdbc.update("INSERT INTO user_group_members(user_id, group_id) VALUES (?, ?)", userId, g.getId());
        }
    }

    @Transactional
    public boolean addUserToGroup(UUID userId, UserGroup group) {
        int changed = jdbc.update("""
                INSERT INTO user_group_members(user_id, group_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, userId, group.getId());
        return changed > 0;
    }

    @Transactional
    public void removeUserMemberships(UUID userId) {
        assertNotSoleSuperAdmin(userId);
        jdbc.update("DELETE FROM user_group_members WHERE user_id = ?", userId);
    }

    /**
     * Страховка от полной потери административного доступа: последнего члена
     * SUPER_ADMIN нельзя лишить членства, заблокировать или отключить через API.
     */
    @Transactional(readOnly = true)
    public void assertNotSoleSuperAdmin(UUID userId) {
        UserGroup superAdmin = groups.findByCode(UserGroup.SUPER_ADMIN_CODE).orElse(null);
        if (superAdmin == null) {
            return;
        }
        Long memberships = jdbc.queryForObject(
                "SELECT count(*) FROM user_group_members WHERE group_id = ?", Long.class, superAdmin.getId());
        Long ownMembership = jdbc.queryForObject(
                "SELECT count(*) FROM user_group_members WHERE group_id = ? AND user_id = ?",
                Long.class, superAdmin.getId(), userId);
        if (ownMembership != null && ownMembership > 0 && memberships != null && memberships <= 1) {
            throw ApiException.unprocessable("Нельзя оставить систему без супер-администратора");
        }
    }

    public List<String> groupCodesOf(UUID userId) {
        return jdbc.queryForList("""
                SELECT g.code FROM user_group_members m
                JOIN user_groups g ON g.id = m.group_id
                WHERE m.user_id = ? ORDER BY g.code
                """, String.class, userId);
    }

    public Map<UUID, List<String>> groupCodesByUserIds(Collection<UUID> userIds) {
        Map<UUID, List<String>> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(", ", userIds.stream().map(id -> "?").toList());
        jdbc.query("""
                SELECT m.user_id, g.code FROM user_group_members m
                JOIN user_groups g ON g.id = m.group_id
                WHERE m.user_id IN (%s) ORDER BY g.code
                """.formatted(placeholders),
                rs -> {
                    UUID uid = UUID.fromString(rs.getString("user_id"));
                    result.computeIfAbsent(uid, k -> new ArrayList<>()).add(rs.getString("code"));
                },
                userIds.toArray());
        return result;
    }

    public Map<UUID, Long> memberCounts() {
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.query("SELECT group_id, count(*) AS c FROM user_group_members GROUP BY group_id",
                rs -> {
                    counts.put(UUID.fromString(rs.getString("group_id")), rs.getLong("c"));
                });
        return counts;
    }

    // ---------- Эффективный доступ (internal для auth-service) ----------

    @Transactional(readOnly = true)
    public AccessView accessOf(UUID userId) {
        List<UUID> groupIds = jdbc.queryForList(
                "SELECT group_id FROM user_group_members WHERE user_id = ?", UUID.class, userId);
        if (groupIds.isEmpty()) {
            return new AccessView(List.of(), List.of());
        }
        List<UserGroup> userGroups = groups.findAllById(groupIds).stream()
                .sorted(Comparator.comparing(UserGroup::getCode))
                .toList();
        List<String> groupCodes = userGroups.stream().map(UserGroup::getCode).toList();
        boolean superAdmin = userGroups.stream().anyMatch(g -> g.isSuperAdmin() && g.isEnabled());
        Set<Permission> perms = superAdmin
                ? EnumSet.allOf(Permission.class)
                : effectivePermissions.effectivePermissions(userGroups.stream()
                        .map(g -> new EffectivePermissionService.GroupPermissions(
                                g.getCode(), Set.copyOf(g.getPermissions()), g.isEnabled()))
                        .toList());
        return new AccessView(groupCodes, permissionNames(perms));
    }

    /** Идемпотентно добавляет пользователя в SUPER_ADMIN (бутстрап первого старта). */
    @Transactional
    public boolean ensureSuperAdmin(UUID userId) {
        UserGroup superAdmin = groups.findByCode(UserGroup.SUPER_ADMIN_CODE).orElse(null);
        if (superAdmin == null) {
            return false;
        }
        boolean added = addUserToGroup(userId, superAdmin);
        if (added) {
            audit.record(Actor.system(), "BOOTSTRAP_SUPERADMIN_ASSIGNED", "user", userId.toString(),
                    Map.of("group", UserGroup.SUPER_ADMIN_CODE));
        }
        return true;
    }

    // ---------- helpers ----------

    private UserGroup loadByCode(String code) {
        return groups.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("Группа не найдена: " + code));
    }

    static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw ApiException.badRequest("Код группы не указан");
        }
        return code.trim().toUpperCase();
    }

    static Set<Permission> parsePermissions(List<String> codes) {
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        if (codes == null) {
            return result;
        }
        List<String> unknown = new ArrayList<>();
        for (String code : codes) {
            try {
                result.add(Permission.valueOf(code.trim()));
            } catch (IllegalArgumentException | NullPointerException e) {
                unknown.add(String.valueOf(code));
            }
        }
        if (!unknown.isEmpty()) {
            throw ApiException.unprocessable("Неизвестные коды прав: " + String.join(", ", unknown));
        }
        return result;
    }

    static List<String> permissionNames(Collection<Permission> perms) {
        return perms.stream().map(Permission::name).sorted().toList();
    }
}
