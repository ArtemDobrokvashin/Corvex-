package ru.corvex.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import ru.corvex.admin.api.ApiException;
import ru.corvex.admin.api.dto.AdminDtos.AdminUserView;
import ru.corvex.admin.api.dto.AdminDtos.CreateUserRequest;
import ru.corvex.admin.api.dto.AdminDtos.PatchUserRequest;
import ru.corvex.admin.client.AuthServiceClient;
import ru.corvex.admin.client.AuthServiceClient.RemotePage;
import ru.corvex.admin.client.AuthServiceClient.RemoteUser;
import ru.corvex.admin.domain.UserGroup;
import ru.corvex.admin.service.AuditService.Actor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Actor ACTOR = new Actor(
            UUID.fromString("22222222-2222-4222-8222-222222222222"), "admin", "192.0.2.9");

    private AuthServiceClient auth;
    private AdminGroupService groupService;
    private AuditService audit;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        auth = mock(AuthServiceClient.class);
        groupService = mock(AdminGroupService.class);
        audit = mock(AuditService.class);
        service = new AdminUserService(auth, groupService, audit);
    }

    @Test
    void createUsesDefaultUserGroupAndAuditsSafeUserFields() {
        RemoteUser remote = remoteUser("ACTIVE");
        UserGroup userGroup = group("USER");
        when(groupService.resolveGroups(List.of("USER"))).thenReturn(List.of(userGroup));
        when(auth.createUser("ivan", "ivan@example.test", "Ivan Petrov",
                "Secret-123", "Sales", 5_000L)).thenReturn(remote);

        AdminUserView result = service.create(new CreateUserRequest(
                "ivan", "ivan@example.test", "Ivan Petrov", "Secret-123",
                "Sales", null, 5_000L), ACTOR);

        assertEquals(USER_ID, result.id());
        assertEquals(List.of("USER"), result.groups());
        verify(auth).createUser("ivan", "ivan@example.test", "Ivan Petrov",
                "Secret-123", "Sales", 5_000L);
        verify(groupService).replaceUserGroups(USER_ID, List.of(userGroup));
        verify(audit).record(ACTOR, "USER_CREATED", "user", USER_ID.toString(),
                Map.of("username", "ivan", "email", "ivan@example.test", "groups", List.of("USER")));
    }

    @Test
    void createWithExplicitGroupsDoesNotSubstituteDefaultGroup() {
        RemoteUser remote = remoteUser("ACTIVE");
        UserGroup sales = group("SALES");
        when(groupService.resolveGroups(List.of("SALES"))).thenReturn(List.of(sales));
        when(auth.createUser(eq("ivan"), any(), any(), any(), any(), isNull())).thenReturn(remote);

        AdminUserView result = service.create(new CreateUserRequest(
                "ivan", "ivan@example.test", "Ivan", "Secret-123",
                null, List.of("SALES"), null), ACTOR);

        assertEquals(List.of("SALES"), result.groups());
        verify(groupService, never()).resolveGroups(List.of("USER"));
    }

    @Test
    void createDisablesNewAccountWhenGroupAssignmentFails() {
        RemoteUser remote = remoteUser("ACTIVE");
        RuntimeException failure = ApiException.conflict("membership conflict");
        when(groupService.resolveGroups(List.of("USER"))).thenReturn(List.of(group("USER")));
        when(auth.createUser(any(), any(), any(), any(), any(), any())).thenReturn(remote);
        doThrow(failure).when(groupService).replaceUserGroups(eq(USER_ID), any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.create(
                new CreateUserRequest("ivan", "ivan@example.test", "Ivan", "Secret-123",
                        null, List.of(), null), ACTOR));

        assertSame(failure, thrown);
        verify(auth).disableUser(USER_ID);
        verify(audit, never()).record(any(), eq("USER_CREATED"), any(), any(), any());
    }

    @Test
    void createCompensationFailureDoesNotHideOriginalAssignmentFailure() {
        RuntimeException failure = new IllegalStateException("membership failed");
        when(groupService.resolveGroups(List.of("USER"))).thenReturn(List.of(group("USER")));
        when(auth.createUser(any(), any(), any(), any(), any(), any())).thenReturn(remoteUser("ACTIVE"));
        doThrow(failure).when(groupService).replaceUserGroups(eq(USER_ID), any());
        doThrow(new IllegalStateException("disable failed")).when(auth).disableUser(USER_ID);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.create(
                new CreateUserRequest("ivan", "ivan@example.test", "Ivan", "Secret-123",
                        null, null, null), ACTOR));

        assertSame(failure, thrown);
    }

    @Test
    void listUsesOnlyLocalMembershipDataForGroupCodes() {
        RemoteUser first = remoteUser("ACTIVE");
        UUID secondId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        RemoteUser second = new RemoteUser(
                secondId, "anna", "anna@example.test", "Anna Smirnova",
                "ldap", "ACTIVE", "Support", List.of("DIRECTOR"),
                List.of("ANALYTICS_VIEW_COMPANY"), 10_000L, 123L,
                false, true, Instant.parse("2026-01-02T03:04:05Z"),
                Instant.parse("2026-01-01T00:00:00Z"));
        when(auth.listUsers("an", "ACTIVE", "Sales", 2, 25))
                .thenReturn(new RemotePage(List.of(first, second), 2, 25, 12, 1));
        when(groupService.groupCodesByUserIds(List.of(first.id(), second.id())))
                .thenReturn(Map.of(USER_ID, List.of("SALES")));

        var result = service.list("an", "ACTIVE", "Sales", 2, 25);

        assertEquals(12, result.totalElements());
        assertEquals(List.of("SALES"), result.content().get(0).groups());
        assertEquals(List.of(), result.content().get(1).groups());
        assertEquals("ldap", result.content().get(1).source());
        assertEquals(10_000L, result.content().get(1).quotaBytes());
    }

    @Test
    void patchSendsOnlyProvidedFieldsAndGuardsNonActiveStatus() {
        when(auth.patchUser(eq(USER_ID), any())).thenReturn(remoteUser("BLOCKED"));
        when(groupService.groupCodesOf(USER_ID)).thenReturn(List.of("SALES"));

        AdminUserView result = service.patch(USER_ID, new PatchUserRequest(
                "New Name", "new@example.test", "Finance", 9_000L, "BLOCKED"), ACTOR);

        assertEquals("BLOCKED", result.status());
        verify(groupService).assertNotSoleSuperAdmin(USER_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(auth).patchUser(eq(USER_ID), patch.capture());
        assertEquals(Set.of("displayName", "email", "department", "quotaBytes", "status"),
                patch.getValue().keySet());
        assertEquals(9_000L, patch.getValue().get("quotaBytes"));
        verify(audit).record(ACTOR, "USER_UPDATED", "user", USER_ID.toString(),
                Map.of("changedFields",
                        List.of("displayName", "email", "department", "quotaBytes", "status")));
    }

    @Test
    void patchActiveOrUnchangedStatusSkipsSoleSuperAdminGuard() {
        when(auth.patchUser(eq(USER_ID), any())).thenReturn(remoteUser("ACTIVE"));
        when(groupService.groupCodesOf(USER_ID)).thenReturn(List.of("USER"));

        service.patch(USER_ID, new PatchUserRequest(null, null, null, null, " active "), ACTOR);
        service.patch(USER_ID, new PatchUserRequest(null, null, null, null, null), ACTOR);

        verify(groupService, never()).assertNotSoleSuperAdmin(USER_ID);
    }

    @Test
    void blockAndDisableGuardBeforeChangingAuthState() {
        service.block(USER_ID, ACTOR);
        service.disable(USER_ID, ACTOR);

        InOrder order = inOrder(groupService, auth, audit);
        order.verify(groupService).assertNotSoleSuperAdmin(USER_ID);
        order.verify(auth).blockUser(USER_ID);
        order.verify(audit).record(ACTOR, "USER_BLOCKED", "user", USER_ID.toString(), Map.of());
        order.verify(groupService).assertNotSoleSuperAdmin(USER_ID);
        order.verify(auth).disableUser(USER_ID);
        order.verify(groupService).removeUserMemberships(USER_ID);
        order.verify(audit).record(ACTOR, "USER_DISABLED", "user", USER_ID.toString(), Map.of());
    }

    @Test
    void disableDoesNotCallAuthWhenSoleSuperAdminGuardRejects() {
        ApiException failure = ApiException.unprocessable("sole superadmin");
        doThrow(failure).when(groupService).assertNotSoleSuperAdmin(USER_ID);

        assertSame(failure, assertThrows(ApiException.class, () -> service.disable(USER_ID, ACTOR)));
        verify(auth, never()).disableUser(USER_ID);
        verify(groupService, never()).removeUserMemberships(USER_ID);
    }

    @Test
    void setGroupsChecksAuthUserThenReplacesMembershipAndReturnsCodes() {
        RemoteUser remote = remoteUser("ACTIVE");
        UserGroup sales = group("SALES");
        when(auth.getUser(USER_ID)).thenReturn(remote);
        when(groupService.resolveGroups(List.of("SALES"))).thenReturn(List.of(sales));

        AdminUserView result = service.setGroups(USER_ID, List.of("SALES"), ACTOR);

        assertEquals(List.of("SALES"), result.groups());
        verify(groupService).replaceUserGroups(USER_ID, List.of(sales));
        verify(audit).record(ACTOR, "USER_GROUPS_CHANGED", "user", USER_ID.toString(),
                Map.of("groups", List.of("SALES")));
    }

    @Test
    void setGroupsPropagatesMissingAuthUserBeforeResolvingGroups() {
        ApiException missing = ApiException.notFound("missing");
        when(auth.getUser(USER_ID)).thenThrow(missing);

        assertSame(missing, assertThrows(ApiException.class,
                () -> service.setGroups(USER_ID, List.of("USER"), ACTOR)));
        verify(groupService, never()).resolveGroups(any());
    }

    private UserGroup group(String code) {
        UserGroup group = new UserGroup();
        group.setId(UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)));
        group.setCode(code);
        group.setName(code);
        group.setEnabled(true);
        group.setPermissions(Set.of());
        assertTrue(group.getCode().equals(code));
        return group;
    }

    private RemoteUser remoteUser(String status) {
        return new RemoteUser(
                USER_ID, "ivan", "ivan@example.test", "Ivan Petrov",
                "local", status, "Sales", List.of("REMOTE"),
                List.of("MAIL_READ_OWN"), 5_000L, 250L,
                true, false, Instant.parse("2026-01-03T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
