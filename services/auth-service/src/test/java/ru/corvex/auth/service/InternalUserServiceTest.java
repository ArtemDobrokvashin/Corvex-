package ru.corvex.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.corvex.auth.api.ApiException;
import ru.corvex.auth.api.dto.AuthDtos.CreateUserRequest;
import ru.corvex.auth.api.dto.AuthDtos.PatchUserRequest;
import ru.corvex.auth.api.dto.AuthDtos.StatsResponse;
import ru.corvex.auth.api.dto.AuthDtos.UserPage;
import ru.corvex.auth.api.dto.AuthDtos.UserView;
import ru.corvex.auth.api.dto.AuthDtos.VerifyPasswordResponse;
import ru.corvex.auth.domain.AppPassword;
import ru.corvex.auth.domain.User;
import ru.corvex.auth.repository.AppPasswordRepository;
import ru.corvex.auth.repository.LoginAttemptRepository;
import ru.corvex.auth.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalUserServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private UserRepository users;
    private AppPasswordRepository appPasswords;
    private LoginAttemptRepository attempts;
    private PasswordEncoder passwordEncoder;
    private SessionRevoker sessionRevoker;
    private InternalUserService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        appPasswords = mock(AppPasswordRepository.class);
        attempts = mock(LoginAttemptRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        sessionRevoker = mock(SessionRevoker.class);
        service = new InternalUserService(users, appPasswords, attempts, passwordEncoder, sessionRevoker);

        when(passwordEncoder.encode(anyString()))
                .thenAnswer(invocation -> "enc(" + invocation.getArgument(0) + ")");
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            if (user.getId() == null) {
                user.setId(USER_ID);
            }
            if (user.getCreatedAt() == null) {
                user.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            }
            return user;
        });
    }

    @Test
    void createNormalizesIdentityUsesDefaultQuotaAndRequiresPasswordChange() {
        UserView result = service.create(new CreateUserRequest(
                "  IVAN ", " Ivan@Example.Test ", "  Ivan Petrov  ",
                "Password123", "Sales", null));

        assertEquals(USER_ID, result.id());
        assertEquals("ivan", result.username());
        assertEquals("ivan@example.test", result.email());
        assertEquals("Ivan Petrov", result.displayName());
        assertEquals(5368709120L, result.quotaBytes());
        assertTrue(result.mustChangePassword());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertEquals("enc(Password123)", captor.getValue().getPasswordHash());
        assertEquals(User.Source.LOCAL, captor.getValue().getSource());
        assertEquals(User.Status.ACTIVE, captor.getValue().getStatus());
    }

    @Test
    void createUsesExplicitQuota() {
        UserView result = service.create(new CreateUserRequest(
                "anna", "anna@example.test", "Anna", "Password123", null, 123L));
        assertEquals(123L, result.quotaBytes());
    }

    @Test
    void createRejectsDuplicateUsernameEmailAndWeakPassword() {
        CreateUserRequest request = new CreateUserRequest(
                "ivan", "ivan@example.test", "Ivan", "Password123", null, null);
        when(users.existsByUsernameIgnoreCase("ivan")).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ApiException.class, () -> service.create(request)).status());

        when(users.existsByUsernameIgnoreCase("ivan")).thenReturn(false);
        when(users.existsByEmailIgnoreCase("ivan@example.test")).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ApiException.class, () -> service.create(request)).status());

        when(users.existsByEmailIgnoreCase("ivan@example.test")).thenReturn(false);
        CreateUserRequest weak = new CreateUserRequest(
                "ivan", "ivan@example.test", "Ivan", "short1", null, null);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                assertThrows(ApiException.class, () -> service.create(weak)).status());
        verify(users, never()).save(any());
    }

    @Test
    void lookupsNormalizeAndReturnViews() {
        User user = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(users.findByUsernameIgnoreCase("ivan")).thenReturn(Optional.of(user));
        when(users.findByEmailIgnoreCase("ivan@example.test")).thenReturn(Optional.of(user));

        assertEquals("ivan", service.get(USER_ID).username());
        assertEquals(USER_ID, service.getByUsername(" IVAN ").id());
        assertEquals(USER_ID, service.getByEmail(" Ivan@EXAMPLE.test ").id());
    }

    @Test
    void lookupsRejectMissingUsers() {
        when(users.findById(USER_ID)).thenReturn(Optional.empty());
        when(users.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("ghost@example.test")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.get(USER_ID)).status());
        assertThrows(ApiException.class, () -> service.getByUsername("ghost"));
        assertThrows(ApiException.class, () -> service.getByEmail("ghost@example.test"));
    }

    @Test
    void listTrimsFiltersParsesStatusClampsPageAndSortsByDisplayName() {
        User user = user();
        ArgumentCaptor<Object> q = ArgumentCaptor.forClass(Object.class);
        when(users.search(any(), any(), any(), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(3);
            return new PageImpl<>(List.of(user), pageable, 201);
        });

        UserPage result = service.list("  ivan ", " blocked ", " Sales ", -3, 999);

        assertEquals(201, result.totalElements());
        assertEquals(2, result.totalPages());
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<User.Status> status = ArgumentCaptor.forClass(User.Status.class);
        ArgumentCaptor<String> department = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(users).search(query.capture(), status.capture(), department.capture(), pageable.capture());
        assertEquals("ivan", query.getValue());
        assertEquals(User.Status.BLOCKED, status.getValue());
        assertEquals("Sales", department.getValue());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(200, pageable.getValue().getPageSize());
        assertEquals(Sort.by("displayName").ascending(), pageable.getValue().getSort());
        assertNotNull(q);
    }

    @Test
    void listTreatsBlankFiltersAsNullAndRejectsUnknownStatus() {
        when(users.search(any(), any(), any(), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(3);
            return new PageImpl<>(List.of(), pageable, 0);
        });

        service.list(" ", null, "", 0, 0);
        verify(users).search(eqNull(), eqNullStatus(), eqNull(), any(Pageable.class));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.list(null, "SUSPENDED", null, 0, 10)).status());
    }

    @Test
    void patchAppliesFieldsClearsDepartmentAndRevokesSessionsForBlockedStatus() {
        User user = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(users.existsByEmailIgnoreCase("new@example.test")).thenReturn(false);

        UserView result = service.patch(USER_ID, new PatchUserRequest(
                "  New Name ", " NEW@Example.Test ", " ", 9_000L, "blocked"));

        assertEquals("New Name", result.displayName());
        assertEquals("new@example.test", result.email());
        assertNull(result.department());
        assertEquals(9_000L, result.quotaBytes());
        assertEquals("BLOCKED", result.status());
        verify(sessionRevoker).revokeAll(USER_ID);
    }

    @Test
    void patchIgnoresBlankNameAndEmailAndAllowsSameEmailWithoutUniquenessCheck() {
        User user = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));

        UserView result = service.patch(USER_ID, new PatchUserRequest(
                " ", " IVAN@EXAMPLE.TEST ", " Finance ", null, "ACTIVE"));

        assertEquals("Ivan Petrov", result.displayName());
        assertEquals("ivan@example.test", result.email());
        assertEquals("Finance", result.department());
        verify(users, never()).existsByEmailIgnoreCase(anyString());
        verify(sessionRevoker, never()).revokeAll(any());
    }

    @Test
    void patchRejectsDuplicateEmailAndUnknownStatus() {
        User user = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(users.existsByEmailIgnoreCase("taken@example.test")).thenReturn(true);

        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.patch(USER_ID, new PatchUserRequest(
                        null, "taken@example.test", null, null, null))).status());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.patch(USER_ID, new PatchUserRequest(
                        null, null, null, null, "SUSPENDED"))).status());
    }

    @Test
    void setStatusRevokesOnlyForNonActiveStatuses() {
        User user = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));

        service.setStatus(USER_ID, User.Status.ACTIVE);
        verify(sessionRevoker, never()).revokeAll(any());

        service.setStatus(USER_ID, User.Status.DISABLED);
        assertEquals(User.Status.DISABLED, user.getStatus());
        verify(sessionRevoker).revokeAll(USER_ID);
    }

    @Test
    void verifyPasswordRejectsMissingAndInactiveUsers() {
        when(users.findByLogin("ghost")).thenReturn(Optional.empty());
        assertFalse(service.verifyPassword("ghost", "Password123").valid());

        User user = user();
        user.setStatus(User.Status.BLOCKED);
        when(users.findByLogin("ivan")).thenReturn(Optional.of(user));
        VerifyPasswordResponse result = service.verifyPassword("ivan", "Password123");
        assertFalse(result.valid());
        assertNull(result.userId());
        assertNull(result.method());
    }

    @Test
    void verifyPasswordPrefersAppPasswordAndRecordsUse() {
        User user = user();
        AppPassword first = appPassword("hash-1");
        AppPassword second = appPassword("hash-2");
        when(users.findByLogin("ivan")).thenReturn(Optional.of(user));
        when(appPasswords.findAllByUserIdOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of(first, second));
        when(passwordEncoder.matches("abcd1234", "hash-1")).thenReturn(false);
        when(passwordEncoder.matches("abcd1234", "hash-2")).thenReturn(true);

        VerifyPasswordResponse result = service.verifyPassword(" IVAN ", "ab-cd 12-34");

        assertTrue(result.valid());
        assertEquals(USER_ID, result.userId());
        assertEquals("APP_PASSWORD", result.method());
        assertNotNull(second.getLastUsedAt());
        verify(appPasswords).save(second);
    }

    @Test
    void verifyPasswordAcceptsMainPasswordOnlyWhenTotpDisabled() {
        User user = user();
        when(users.findByLogin("ivan")).thenReturn(Optional.of(user));
        when(appPasswords.findAllByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());
        when(passwordEncoder.matches("Password123", "hash")).thenReturn(true);

        assertEquals("PASSWORD", service.verifyPassword("ivan", "Password123").method());

        user.setTotpEnabled(true);
        assertFalse(service.verifyPassword("ivan", "Password123").valid());
    }

    @Test
    void verifyPasswordRejectsNoMatchingPasswordAndNullInput() {
        User user = user();
        when(users.findByLogin("ivan")).thenReturn(Optional.of(user));
        when(appPasswords.findAllByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());

        assertFalse(service.verifyPassword("ivan", "wrong").valid());
        assertFalse(service.verifyPassword("ivan", null).valid());
    }

    @Test
    void statsWithoutDepartmentIncludeSuccessfulLoginCounts() {
        when(users.countAllByDepartment(null)).thenReturn(20L);
        when(users.countByStatusAndDepartment(User.Status.ACTIVE, null)).thenReturn(15L);
        when(users.countByStatusAndDepartment(User.Status.BLOCKED, null)).thenReturn(2L);
        when(users.countByStatusAndDepartment(User.Status.DISABLED, null)).thenReturn(3L);
        when(attempts.countBySuccessTrueAndCreatedAtAfter(any(Instant.class))).thenReturn(10L, 70L);

        StatsResponse stats = service.stats(" ");

        assertEquals(20L, stats.totalUsers());
        assertEquals(15L, stats.activeUsers());
        assertEquals(10L, stats.loginsLast24h());
        assertEquals(70L, stats.loginsLast7d());
        assertNull(stats.department());
    }

    @Test
    void statsWithDepartmentDoNotFabricateLoginCounts() {
        when(users.countAllByDepartment("Sales")).thenReturn(5L);
        when(users.countByStatusAndDepartment(User.Status.ACTIVE, "Sales")).thenReturn(4L);
        when(users.countByStatusAndDepartment(User.Status.BLOCKED, "Sales")).thenReturn(1L);
        when(users.countByStatusAndDepartment(User.Status.DISABLED, "Sales")).thenReturn(0L);

        StatsResponse stats = service.stats(" Sales ");

        assertEquals("Sales", stats.department());
        assertNull(stats.loginsLast24h());
        assertNull(stats.loginsLast7d());
        verify(attempts, never()).countBySuccessTrueAndCreatedAtAfter(any());
    }

    private String eqNull() {
        return org.mockito.ArgumentMatchers.isNull();
    }

    private User.Status eqNullStatus() {
        return org.mockito.ArgumentMatchers.isNull();
    }

    private AppPassword appPassword(String hash) {
        AppPassword password = new AppPassword();
        password.setId(UUID.randomUUID());
        password.setUserId(USER_ID);
        password.setLabel("Mail client");
        password.setPasswordHash(hash);
        return password;
    }

    private User user() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("ivan");
        user.setEmail("ivan@example.test");
        user.setDisplayName("Ivan Petrov");
        user.setPasswordHash("hash");
        user.setSource(User.Source.LOCAL);
        user.setStatus(User.Status.ACTIVE);
        user.setDepartment("Sales");
        user.setQuotaBytes(5_000L);
        user.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return user;
    }
}
