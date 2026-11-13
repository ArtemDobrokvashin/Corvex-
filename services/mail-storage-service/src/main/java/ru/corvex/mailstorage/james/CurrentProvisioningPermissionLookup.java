package ru.corvex.mailstorage.james;

import java.util.UUID;

/** Authoritative current provisioning permission; errors must fail closed. */
interface CurrentProvisioningPermissionLookup {
    boolean canManageUsers(UUID actorId);
}
