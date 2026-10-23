package ru.corvex.admin.domain;

import org.junit.jupiter.api.Test;
import ru.corvex.admin.rbac.Permission;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserGroupTest {

    @Test
    void defaultsToEnabledWithEmptyPermissionsAndGeneratedCreationTime() {
        UserGroup group = new UserGroup();

        assertTrue(group.isEnabled());
        assertEquals(EnumSet.noneOf(Permission.class), group.getPermissions());
        group.onCreate();
        assertNotNull(group.getCreatedAt());
    }

    @Test
    void onCreateDoesNotOverwriteExistingCreationTime() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        UserGroup group = new UserGroup();
        group.setCreatedAt(createdAt);

        group.onCreate();

        assertEquals(createdAt, group.getCreatedAt());
    }

    @Test
    void superAdminIsDetectedOnlyByExactCanonicalCode() {
        UserGroup group = new UserGroup();
        group.setId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
        group.setCode(UserGroup.SUPER_ADMIN_CODE);
        assertTrue(group.isSuperAdmin());

        group.setCode("super_admin");
        assertFalse(group.isSuperAdmin());
        group.setCode(null);
        assertFalse(group.isSuperAdmin());
    }
}
