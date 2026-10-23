package ru.corvex.admin.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.corvex.admin.client.AuthServiceClient;
import ru.corvex.admin.client.AuthServiceClient.RemoteUser;
import ru.corvex.admin.service.AdminGroupService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BootstrapSuperAdminAssignmentTest {

    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void runAssignsExistingBootstrapUserOnBackgroundThread() throws Exception {
        AuthServiceClient auth = mock(AuthServiceClient.class);
        AdminGroupService groups = mock(AdminGroupService.class);
        CountDownLatch assigned = new CountDownLatch(1);
        when(auth.findByUsername("root-admin")).thenReturn(Optional.of(remoteUser()));
        when(groups.ensureSuperAdmin(ADMIN_ID)).thenAnswer(invocation -> {
            assigned.countDown();
            return true;
        });

        new BootstrapSuperAdminAssignment(auth, groups, "root-admin").run(null);

        assertTrue(assigned.await(2, TimeUnit.SECONDS));
        verify(groups).ensureSuperAdmin(ADMIN_ID);
    }

    @Test
    void runStopsCleanlyWhenSuperAdminGroupHasNotBeenSeeded() throws Exception {
        AuthServiceClient auth = mock(AuthServiceClient.class);
        AdminGroupService groups = mock(AdminGroupService.class);
        CountDownLatch checked = new CountDownLatch(1);
        when(auth.findByUsername("admin")).thenReturn(Optional.of(remoteUser()));
        when(groups.ensureSuperAdmin(ADMIN_ID)).thenAnswer(invocation -> {
            checked.countDown();
            return false;
        });

        new BootstrapSuperAdminAssignment(auth, groups, "admin").run(null);

        assertTrue(checked.await(2, TimeUnit.SECONDS));
    }

    @Test
    void retryLoopSwallowsLookupFailureAndStopsOnInterruption() {
        AuthServiceClient auth = mock(AuthServiceClient.class);
        AdminGroupService groups = mock(AdminGroupService.class);
        when(auth.findByUsername("admin")).thenThrow(new IllegalStateException("auth is starting"));
        BootstrapSuperAdminAssignment assignment =
                new BootstrapSuperAdminAssignment(auth, groups, "admin");

        Thread.currentThread().interrupt();
        try {
            ReflectionTestUtils.invokeMethod(assignment, "assignWithRetries");
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        verify(auth).findByUsername("admin");
    }

    private RemoteUser remoteUser() {
        return new RemoteUser(
                ADMIN_ID, "root-admin", "root@example.test", "Root Admin",
                "local", "ACTIVE", null, List.of(), List.of(), 0L, 0L,
                true, false, null, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
