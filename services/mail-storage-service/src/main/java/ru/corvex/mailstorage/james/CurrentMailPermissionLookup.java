package ru.corvex.mailstorage.james;

import java.util.UUID;

/** Authoritative current RBAC lookup; errors must never become permission grants. */
interface CurrentMailPermissionLookup {
    boolean canReadOwnMail(UUID ownerId);
}
