package ru.corvex.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import ru.corvex.admin.api.ApiException;
import ru.corvex.admin.api.dto.AdminDtos.AccessView;
import ru.corvex.admin.api.dto.AdminDtos.CreateGroupRequest;
import ru.corvex.admin.api.dto.AdminDtos.GroupView;
import ru.corvex.admin.domain.UserGroup;
import ru.corvex.admin.rbac.EffectivePermissionService;
import ru.corvex.admin.rbac.Permission;
import ru.corvex.admin.repository.UserGroupRepository;
import ru.corvex.admin.service.AuditService.Actor;

import java.sql.ResultSet;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminGroupServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SALES_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SUPPORT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID SUPER_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Actor ACTOR = new Actor(
            UUID.fromString("66666666-6666-4666-8666-666666666666"), "root", "192.0.2.5");

    private UserGroupRepository groups;
    private JdbcTemplate jdbc;
    private AuditService audit;
    private AdminGroupService service;

    @BeforeEach
    void setUp() {
        groups = mock(UserGroupRepository.class);
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        service = new AdminGroupService(groups, jdbc, new EffectivePermissionService(), audit);
    }

    @Test
    void listGroupsIncludesMemberCountsAndAllPermissionsForSuperAdmin() throws Exception {
        UserGroup sales = group(SALES_ID, "SALES", false, true, Permission.MAIL_READ_OWN);
        UserGroup superAdmin = group(SUPER_ID, UserGroup.SUPER_ADMIN_CODE, true, true);
        when(groups.findAllByOrderByCodeAsc()).thenReturn(List.of(sales, superAdmin));
        simulateMemberCounts(Map.of(SALES_ID, 3L, SUPER_ID, 1L));

        List<GroupView> result = service.listGroups();

        assertEquals(2, result.size());
        assertEquals(3, result.get(0).memberCount());
        assertEquals(1, result.get(1).memberCount());
        assertEquals(Permission.values().length, result.get(1).permissions().size());
        assertTrue(result.get(1).permissions().contains(Permission.ADMIN_SERVER_CONFIG.name()));
    }

    @Test
    void createGroupNormalizesCodeParsesPermissionsAndAudits() {
        when(groups.existsByCode("SALES")).thenReturn(false);
        when(groups.save(any(UserGroup.class))).thenAnswer(invocation -> {
            UserGroup group = invocation.getArgument(0);
            group.setId(SALES_ID);
            return group;
        });

        GroupView result = service.createGroup(new CreateGroupRequest(
                " sales ", " Продажи ", List.of("MAIL_READ_OWN", " CHAT_READ ", "MAIL_READ_OWN")), ACTOR);

        assertEquals("SALES", result.code());
        assertEquals("Продажи", result.name());
        assertFalse(result.isSystem());
        assertTrue(result.enabled());
        assertEquals(List.of("CHAT_READ", "MAIL_READ_OWN"), result.permissions());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(ACTOR), eq("GROUP_CREATED"), eq("group"), eq("SALES"), details.capture());
        assertEquals(List.of("CHAT_READ", "MAIL_READ_OWN"), details.getValue().get("permissions"));
    }

    @Test
    void createGroupRejectsDuplicateAndUnknownPermission() {
        when(groups.existsByCode("SALES")).thenReturn(true);

        ApiException duplicate = assertThrows(ApiException.class, () -> service.createGroup(
                new CreateGroupRequest("sales", "Продажи", List.of()), ACTOR));
        assertEquals(HttpStatus.CONFLICT, duplicate.status());

        when(groups.existsByCode("SALES")).thenReturn(false);
        ApiException unknown = assertThrows(ApiException.class, () -> service.createGroup(
                new CreateGroupRequest("sales", "Продажи", List.of("NO_SUCH_PERMISSION")), ACTOR));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, unknown.status());
        verify(groups, never()).save(any());
    }

    @Test
    void setPermissionsAuditsAddedAndRemovedCodes() throws Exception {
        UserGroup sales = group(SALES_ID, "SALES", false, true,
                Permission.MAIL_READ_OWN, Permission.MAIL_SEND_INTERNAL);
        when(groups.findByCode("SALES")).thenReturn(Optional.of(sales));
        simulateMemberCounts(Map.of(SALES_ID, 4L));

        GroupView result = service.setPermissions(
                "sales", List.of("MAIL_READ_OWN", "CHAT_WRITE"), ACTOR);

        assertEquals(List.of("CHAT_WRITE", "MAIL_READ_OWN"), result.permissions());
        assertEquals(4, result.memberCount());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(ACTOR), eq("GROUP_PERMISSIONS_CHANGED"), eq("group"), eq("SALES"), details.capture());
        assertEquals(List.of("CHAT_WRITE"), details.getValue().get("added"));
        assertEquals(List.of("MAIL_SEND_INTERNAL"), details.getValue().get("removed"));
    }

    @Test
    void setPermissionsRejectsSuperAdminAndMissingGroup() {
        UserGroup superAdmin = group(SUPER_ID, UserGroup.SUPER_ADMIN_CODE, true, true);
        when(groups.findByCode(UserGroup.SUPER_ADMIN_CODE)).thenReturn(Optional.of(superAdmin));
        when(groups.findByCode("MISSING")).thenReturn(Optional.empty());

        ApiException locked = assertThrows(ApiException.class,
                () -> service.setPermissions("super_admin", List.of(), ACTOR));
        ApiException missing = assertThrows(ApiException.class,
                () -> service.setPermissions("missing", List.of(), ACTOR));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, locked.status());
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    @Test
    void resolveGroupsNormalizesDeduplicatesAndHandlesNull() {
        UserGroup sales = group(SALES_ID, "SALES", false, true);
        UserGroup support = group(SUPPORT_ID, "SUPPORT", false, true);
        when(groups.findByCode("SALES")).thenReturn(Optional.of(sales));
        when(groups.findByCode("SUPPORT")).thenReturn(Optional.of(support));

        assertEquals(List.of(), service.resolveGroups(null));
        assertEquals(List.of(sales, support),
                service.resolveGroups(List.of(" sales ", "SUPPORT", "SALES")));
        assertThrows(ApiException.class, () -> service.resolveGroups(List.of(" ")));
    }

    @Test
    void replaceUserGroupsKeepsSuperAdminWithoutSoleAdminCheck() {
        UserGroup superAdmin = group(SUPER_ID, UserGroup.SUPER_ADMIN_CODE, true, true);

        service.replaceUserGroups(USER_ID, List.of(superAdmin));

        verify(groups, never()).findByCode(UserGroup.SUPER_ADMIN_CODE);
        verify(jdbc).update("DELETE FROM user_group_members WHERE user_id = ?", USER_ID);
        verify(jdbc).update("INSERT INTO user_group_members(user_id, group_id) VALUES (?, ?)", USER_ID, SUPER_ID);
    }

    @Test
    void replaceUserGroupsChecksSoleSuperAdminBeforeDeletingMemberships() {
        UserGroup sales = group(SALES_ID, "SALES", false, true);
        UserGroup superAdmin = group(SUPER_ID, UserGroup.SUPER_ADMIN_CODE, true, true);
        stubSuperAdminCounts(superAdmin, 2L, 1L);

        service.replaceUserGroups(USER_ID, List.of(sales));

        verify(jdbc).update("DELETE FROM user_group_members WHERE user_id = ?", USER_ID);
        verify(jdbc).update("INSERT INTO user_group_members(user_id, group_id) VALUES (?, ?)", USER_ID, SALES_ID);
    }

    @Test
    void addUserToGroupReturnsWhetherInsertChangedMembership() {
        UserGroup sales = group(SALES_ID, "SALES", false, true);
        when(jdbc.update(contains("ON CONFLICT DO NOTHING"), eq(USER_ID), eq(SALES_ID))).thenReturn(1, 0);

        assertTrue(service.addUserToGroup(USER_ID, sales));
        assertFalse(service.addUserToGroup(USER_ID, sales));
    }

    @Test
    void soleSuperAdminCannotLoseMembership() {
        UserGroup superAdmin = group(SUPER_ID, UserGroup.SUPER_ADMIN_CODE, true, true);
        stubSuperAdminCounts(superAdmin, 1L, 1L);

        ApiException error = assertThrows(ApiException.class,
                () -> service.removeUserMemberships(USER_ID));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.status());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void superAdminGuardPassesWhenGroupMissingUserNotMemberOrAnotherAdminRemains() {
        UserGroup superAdmin = group(SUPER_ID, UserGroup.SUPER_ADMIN_CODE, true, true);
        when(groups.findByCode(UserGroup.SUPER_ADMIN_CODE)).thenReturn(Optional.empty());
        service.assertNotSoleSuperAdmin(USER_ID);

        stubSuperAdminCounts(superAdmin, 1L, 0L);
        service.assertNotSoleSuperAdmin(USER_ID);

        stubSuperAdminCounts(superAdmin, null, null);
        service.assertNotSoleSuperAdmin(USER_ID);
    }

    @Test
    void groupCodeQueriesCoverSingleBatchAndCounts() throws Exception {
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("SALES", "USER"));
        assertEquals(List.of("SALES", "USER"), service.groupCodesOf(USER_ID));
        assertEquals(Map.of(), service.groupCodesByUserIds(List.of()));

        simulateMembershipRows(List.of(
                new MembershipRow(USER_ID, "SALES"),
                new MembershipRow(USER_ID, "USER"),
                new MembershipRow(OTHER_ID, "SUPPORT")));
        assertEquals(Map.of(
                        USER_ID, List.of("SALES", "USER"),
                        OTHER_ID, List.of("SUPPORT")),
                service.groupCodesByUserIds(List.of(USER_ID, OTHER_ID)));

        simulateMemberCounts(Map.of(SALES_ID, 8L));
        assertEquals(Map.of(SALES_ID, 8L), service.memberCounts());
    }

    @Test
    void accessOfReturnsEmptyWhenUserHasNoGroups() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(List.of());

        AccessView access = service.accessOf(USER_ID);

        assertEquals(List.of(), access.groups());
        assertEquals(List.of(), access.permissions());
    }

    @Test
    void accessOfSortsGroupsAndIgnoresDisabledGroupPermissions() {
        UserGroup sales = group(SALES_ID, "SALES", false, true,
                Permission.MAIL_READ_OWN, Permission.CHAT_READ);
        UserGroup disabled = group(SUPPORT_ID, "AAA_DISABLED", false, false, Permission.ADMIN_BACKUP);
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class)))
                .thenReturn(List.of(SALES_ID, SUPPORT_ID));
        when(groups.findAllById(List.of(SALES_ID, SUPPORT_ID))).thenReturn(List.of(sales, disabled));

        AccessView access = service.accessOf(USER_ID);

        assertEquals(List.of("AAA_DISABLED", "SALES"), access.groups());
        assertEquals(List.of("CHAT_READ", "MAIL_READ_OWN"), access.permissions());
    }

    @Test
    void accessOfEnabledSuperAdminContainsEveryPermissionButDisabledSuperAdminDoesNot() {
        UserGroup superAdmin = group(SUPER_ID, UserGroup.SUPER_ADMIN_CODE, true, true);
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class)))
                .thenReturn(List.of(SUPER_ID));
        when(groups.findAllById(List.of(SUPER_ID))).thenReturn(List.of(superAdmin));

        AccessView enabled = service.accessOf(USER_ID);
        assertEquals(AdminGroupService.permissionNames(EnumSet.allOf(Permission.class)), enabled.permissions());

        superAdmin.setEnabled(false);
        AccessView disabled = service.accessOf(USER_ID);
        assertEquals(List.of(), disabled.permissions());
    }

    @Test
    void ensureSuperAdminHandlesMissingGroupNewAndExistingMembership() {
        when(groups.findByCode(UserGroup.SUPER_ADMIN_CODE)).thenReturn(Optional.empty());
        assertFalse(service.ensureSuperAdmin(USER_ID));

        UserGroup superAdmin = group(SUPER_ID, UserGroup.SUPER_ADMIN_CODE, true, true);
        when(groups.findByCode(UserGroup.SUPER_ADMIN_CODE)).thenReturn(Optional.of(superAdmin));
        when(jdbc.update(contains("ON CONFLICT DO NOTHING"), eq(USER_ID), eq(SUPER_ID))).thenReturn(1, 0);

        assertTrue(service.ensureSuperAdmin(USER_ID));
        assertTrue(service.ensureSuperAdmin(USER_ID));
        verify(audit).record(eq(Actor.system()), eq("BOOTSTRAP_SUPERADMIN_ASSIGNED"),
                eq("user"), eq(USER_ID.toString()), any());
    }

    @Test
    void codeAndPermissionHelpersValidateInput() {
        assertEquals("SALES-1", AdminGroupService.normalizeCode(" sales-1 "));
        assertThrows(ApiException.class, () -> AdminGroupService.normalizeCode(null));
        assertThrows(ApiException.class, () -> AdminGroupService.normalizeCode(" "));
        assertEquals(Set.of(), AdminGroupService.parsePermissions(null));
        ApiException error = assertThrows(ApiException.class,
                () -> AdminGroupService.parsePermissions(Arrays.asList("MAIL_READ_OWN", null, "BAD")));
        assertTrue(error.getMessage().contains("null"));
        assertTrue(error.getMessage().contains("BAD"));
        assertEquals(List.of("CHAT_READ", "MAIL_READ_OWN"),
                AdminGroupService.permissionNames(Set.of(Permission.MAIL_READ_OWN, Permission.CHAT_READ)));
    }

    private void stubSuperAdminCounts(UserGroup superAdmin, Long total, Long own) {
        when(groups.findByCode(UserGroup.SUPER_ADMIN_CODE)).thenReturn(Optional.of(superAdmin));
        when(jdbc.queryForObject(
                eq("SELECT count(*) FROM user_group_members WHERE group_id = ?"),
                eq(Long.class), any(Object[].class))).thenReturn(total);
        when(jdbc.queryForObject(
                startsWith("SELECT count(*) FROM user_group_members WHERE group_id = ? AND"),
                eq(Long.class), any(Object[].class))).thenReturn(own);
    }

    private void simulateMemberCounts(Map<UUID, Long> counts) {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            for (Map.Entry<UUID, Long> count : counts.entrySet()) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("group_id")).thenReturn(count.getKey().toString());
                when(rs.getLong("c")).thenReturn(count.getValue());
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(startsWith("SELECT group_id"), any(RowCallbackHandler.class));
    }

    private void simulateMembershipRows(List<MembershipRow> rows) {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            for (MembershipRow row : rows) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("user_id")).thenReturn(row.userId().toString());
                when(rs.getString("code")).thenReturn(row.code());
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(contains("SELECT m.user_id"), any(RowCallbackHandler.class), any(Object[].class));
    }

    private UserGroup group(UUID id, String code, boolean system, boolean enabled, Permission... permissions) {
        UserGroup group = new UserGroup();
        group.setId(id);
        group.setCode(code);
        group.setName(code);
        group.setSystem(system);
        group.setEnabled(enabled);
        Set<Permission> values = EnumSet.noneOf(Permission.class);
        values.addAll(Arrays.asList(permissions));
        group.setPermissions(values);
        return group;
    }

    private record MembershipRow(UUID userId, String code) {
    }
}
