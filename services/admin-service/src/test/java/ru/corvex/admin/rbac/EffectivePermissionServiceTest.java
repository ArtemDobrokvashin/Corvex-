package ru.corvex.admin.rbac;

import org.junit.jupiter.api.Test;
import ru.corvex.admin.rbac.EffectivePermissionService.GroupPermissions;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectivePermissionServiceTest {

    private final EffectivePermissionService service = new EffectivePermissionService();

    @Test
    void unionsPermissionsAcrossEnabledGroupsOnly() {
        Set<Permission> result = service.effectivePermissions(List.of(
                new GroupPermissions("USER", Set.of(Permission.MAIL_READ_OWN, Permission.CHAT_READ), true),
                new GroupPermissions("ACCOUNTANT", Set.of(Permission.MAIL_BULK_SEND), true),
                new GroupPermissions("DISABLED_GROUP", Set.of(Permission.ADMIN_USERS_MANAGE), false)));

        assertEquals(Set.of(Permission.MAIL_READ_OWN, Permission.CHAT_READ, Permission.MAIL_BULK_SEND), result);
    }

    @Test
    void defaultRolesMatchSeedExpectations() {
        assertEquals(Permission.values().length,
                EffectivePermissionService.defaultPermissionsOf("SUPER_ADMIN").size());
        assertTrue(EffectivePermissionService.defaultPermissionsOf("ACCOUNTANT").contains(Permission.MAIL_BULK_SEND));
        assertTrue(EffectivePermissionService.defaultPermissionsOf("MANAGER").contains(Permission.ANALYTICS_VIEW_OWN_UNIT));
        assertTrue(EffectivePermissionService.defaultPermissionsOf("USER").stream()
                .noneMatch(p -> p.name().startsWith("ADMIN_")));
    }
}
