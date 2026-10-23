package ru.corvex.admin.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.corvex.admin.api.dto.AdminDtos.AccessView;
import ru.corvex.admin.api.dto.AdminDtos.AdminUserView;
import ru.corvex.admin.api.dto.AdminDtos.AnalyticsResponse;
import ru.corvex.admin.api.dto.AdminDtos.BackupResponse;
import ru.corvex.admin.api.dto.AdminDtos.CreateGroupRequest;
import ru.corvex.admin.api.dto.AdminDtos.CreateUserRequest;
import ru.corvex.admin.api.dto.AdminDtos.GroupView;
import ru.corvex.admin.api.dto.AdminDtos.HealthResponse;
import ru.corvex.admin.api.dto.AdminDtos.PageResult;
import ru.corvex.admin.api.dto.AdminDtos.PatchUserRequest;
import ru.corvex.admin.api.dto.AdminDtos.SetGroupsRequest;
import ru.corvex.admin.api.dto.AdminDtos.SetPermissionsRequest;
import ru.corvex.admin.client.AuthServiceClient;
import ru.corvex.admin.client.AuthServiceClient.RemoteUser;
import ru.corvex.admin.service.AdminGroupService;
import ru.corvex.admin.service.AdminSystemService;
import ru.corvex.admin.service.AdminUserService;
import ru.corvex.admin.service.AuditService;
import ru.corvex.admin.service.AuditService.Actor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllersTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTOR_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Actor ACTOR = new Actor(ACTOR_ID, "root", "203.0.113.4");

    private AdminUserService users;
    private AdminGroupService groups;
    private AdminSystemService system;
    private AuditService audit;
    private AuthServiceClient auth;
    private AdminUserController userController;
    private AdminGroupController groupController;
    private AdminSystemController systemController;
    private InternalAccessController internalController;
    private Jwt jwt;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        users = mock(AdminUserService.class);
        groups = mock(AdminGroupService.class);
        system = mock(AdminSystemService.class);
        audit = mock(AuditService.class);
        auth = mock(AuthServiceClient.class);
        userController = new AdminUserController(users);
        groupController = new AdminGroupController(groups);
        systemController = new AdminSystemController(system, audit, auth);
        internalController = new InternalAccessController(groups);
        jwt = jwt(ACTOR_ID.toString(), "root");
        request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.20");
        request.addHeader("X-Real-IP", " 203.0.113.4 ");
    }

    @Test
    void userControllerDelegatesAllEndpointsWithActorFromJwtAndProxyHeader() {
        AdminUserView view = userView();
        PageResult<AdminUserView> page = new PageResult<>(List.of(view), 1, 20, 1, 1);
        CreateUserRequest create = new CreateUserRequest(
                "ivan", "ivan@example.test", "Ivan", "Secret", "Sales", null, null);
        PatchUserRequest patch = new PatchUserRequest("Ivan P.", null, null, 10L, null);
        when(users.list("iv", "ACTIVE", "Sales", 1, 20)).thenReturn(page);
        when(users.create(create, ACTOR)).thenReturn(view);
        when(users.patch(USER_ID, patch, ACTOR)).thenReturn(view);
        when(users.setGroups(USER_ID, List.of("USER"), ACTOR)).thenReturn(view);

        assertSame(page, userController.list("iv", "ACTIVE", "Sales", 1, 20));
        assertSame(view, userController.create(create, jwt, request));
        assertSame(view, userController.patch(USER_ID, patch, jwt, request));
        userController.disable(USER_ID, jwt, request);
        userController.block(USER_ID, jwt, request);
        assertSame(view, userController.setGroups(
                USER_ID, new SetGroupsRequest(List.of("USER")), jwt, request));

        verify(users).disable(USER_ID, ACTOR);
        verify(users).block(USER_ID, ACTOR);
    }

    @Test
    void groupControllerDelegatesCreateListAndPermissions() {
        GroupView view = new GroupView("SALES", "Sales", false, true,
                List.of("MAIL_READ_OWN"), 2);
        CreateGroupRequest create = new CreateGroupRequest(
                "SALES", "Sales", List.of("MAIL_READ_OWN"));
        when(groups.listGroups()).thenReturn(List.of(view));
        when(groups.createGroup(create, ACTOR)).thenReturn(view);
        when(groups.setPermissions("SALES", List.of("CHAT_READ"), ACTOR)).thenReturn(view);

        assertEquals(List.of(view), groupController.list());
        assertSame(view, groupController.create(create, jwt, request));
        assertSame(view, groupController.setPermissions(
                "SALES", new SetPermissionsRequest(List.of("CHAT_READ")), jwt, request));
    }

    @Test
    void systemControllerDelegatesSettingsAuditHealthAndBackup() {
        Map<String, String> settings = Map.of("mail.maxMessageSizeMb", "25");
        PageResult<ru.corvex.admin.api.dto.AdminDtos.AuditView> auditPage =
                new PageResult<>(List.of(), 0, 50, 0, 0);
        HealthResponse health = new HealthResponse(List.of(), 100L, -1);
        BackupResponse backup = new BackupResponse("REQUESTED", "note");
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T00:00:00Z");
        when(system.settings()).thenReturn(settings);
        when(system.patchSettings(settings, ACTOR)).thenReturn(Map.of("mail.maxMessageSizeMb", "50"));
        when(audit.query(ACTOR_ID, "USER_CREATED", from, to, 2, 10)).thenReturn(auditPage);
        when(system.health()).thenReturn(health);
        when(system.requestBackup(ACTOR)).thenReturn(backup);

        assertSame(settings, systemController.settings());
        assertEquals("50", systemController.patchSettings(settings, jwt, request)
                .get("mail.maxMessageSizeMb"));
        assertSame(auditPage, systemController.audit(ACTOR_ID, "USER_CREATED", from, to, 2, 10));
        assertSame(health, systemController.health());
        ResponseEntity<BackupResponse> response = systemController.backup(jwt, request);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertSame(backup, response.getBody());
    }

    @Test
    void companyAnalyticsUsesRequestedDepartmentWithoutLookingUpActor() {
        AnalyticsResponse expected = analytics("COMPANY", "Finance");
        when(system.analytics("COMPANY", "Finance")).thenReturn(expected);

        AnalyticsResponse result = systemController.analytics(
                "30d", "Finance", jwt, authentication("ANALYTICS_VIEW_COMPANY"));

        assertSame(expected, result);
        verify(auth, never()).getUser(ACTOR_ID);
    }

    @Test
    void unitAnalyticsIgnoresRequestedDepartmentAndUsesActorDepartment() {
        AnalyticsResponse expected = analytics("OWN_UNIT", "Sales");
        when(auth.getUser(ACTOR_ID)).thenReturn(remoteUser("Sales"));
        when(system.analytics("OWN_UNIT", "Sales")).thenReturn(expected);

        AnalyticsResponse result = systemController.analytics(
                null, "Finance", jwt, authentication("ANALYTICS_VIEW_OWN_UNIT"));

        assertSame(expected, result);
    }

    @Test
    void unitAnalyticsReturnsZerosWhenActorHasNoDepartmentOrIdentity() {
        when(auth.getUser(ACTOR_ID)).thenReturn(remoteUser(" "));

        AnalyticsResponse blankDepartment = systemController.analytics(
                null, "Finance", jwt, authentication("ANALYTICS_VIEW_OWN_UNIT"));
        AnalyticsResponse anonymous = systemController.analytics(
                null, "Finance", null, authentication("ANALYTICS_VIEW_OWN_UNIT"));

        assertEquals(0, blankDepartment.usersTotal());
        assertNull(blankDepartment.department());
        assertEquals(0, anonymous.usersTotal());
        verify(system, never()).analytics(eq("OWN_UNIT"), eq("Finance"));
    }

    @Test
    void internalAccessControllerDelegatesToGroupService() {
        AccessView access = new AccessView(List.of("USER"), List.of("MAIL_READ_OWN"));
        when(groups.accessOf(USER_ID)).thenReturn(access);

        assertSame(access, internalController.access(USER_ID));
    }

    @Test
    void actorsExtractIdentityAndProxyIpWithSafeFallbacks() {
        assertEquals(ACTOR, Actors.from(jwt, request));

        MockHttpServletRequest forwarded = new MockHttpServletRequest();
        forwarded.setRemoteAddr("192.0.2.20");
        forwarded.addHeader("X-Forwarded-For", "198.51.100.1, , 203.0.113.9, ");
        assertEquals("203.0.113.9", Actors.from(jwt, forwarded).ip());

        MockHttpServletRequest blankForwarded = new MockHttpServletRequest();
        blankForwarded.setRemoteAddr("192.0.2.20");
        blankForwarded.addHeader("X-Forwarded-For", " , , ");
        assertEquals("192.0.2.20", Actors.from(null, blankForwarded).ip());

        Jwt malformedSubject = jwt("not-a-uuid", "someone");
        Actor malformed = Actors.from(malformedSubject, null);
        assertNull(malformed.id());
        assertEquals("someone", malformed.name());
        assertNull(malformed.ip());
        assertNull(Actors.userId(null));
        assertNull(Actors.userId(malformedSubject));
        assertEquals(ACTOR_ID, Actors.userId(jwt));
    }

    private Authentication authentication(String authority) {
        return new UsernamePasswordAuthenticationToken(
                "actor", "n/a", List.of(new SimpleGrantedAuthority(authority)));
    }

    private Jwt jwt(String subject, String username) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("preferred_username", username)
                .build();
    }

    private AdminUserView userView() {
        return new AdminUserView(
                USER_ID, "ivan", "ivan@example.test", "Ivan Petrov",
                "local", "ACTIVE", "Sales", List.of("USER"), 5_000L, 0L,
                true, false, null, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private RemoteUser remoteUser(String department) {
        return new RemoteUser(
                ACTOR_ID, "root", "root@example.test", "Root",
                "local", "ACTIVE", department, List.of("ADMIN"),
                List.of("ANALYTICS_VIEW_OWN_UNIT"), 0L, 0L,
                false, true, null, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private AnalyticsResponse analytics(String scope, String department) {
        return new AnalyticsResponse(scope, department, 10, 8, 1, 1, 3L, 9L, Instant.now());
    }
}
