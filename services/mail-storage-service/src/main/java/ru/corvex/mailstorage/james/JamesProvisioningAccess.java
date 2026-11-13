package ru.corvex.mailstorage.james;

import java.io.IOException;
import java.security.SecureRandom;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import ru.corvex.mailstorage.client.AuthUserClient;

/**
 * Disconnected authorization boundary, NOT a bean or endpoint. Authentication
 * must come from Spring's JWT signature-validation filter, never client input.
 * Current directory and permission checks fail closed, but are not atomic with
 * creation and cannot revoke an already-started remote request.
 */
final class JamesProvisioningAccess {
    private final AuthUserClient users;
    private final CurrentProvisioningPermissionLookup permissions;
    private final JamesProvisioningCoordinator coordinator;
    private final JamesProvisioningDiscovery discovery;
    private final Clock clock;
    private final SecureRandom random;

    JamesProvisioningAccess(AuthUserClient users, CurrentProvisioningPermissionLookup permissions,
                            JamesProvisioningCoordinator coordinator, JamesProvisioningDiscovery discovery,
                            Clock clock, SecureRandom random) {
        this.users = Objects.requireNonNull(users);
        this.permissions = Objects.requireNonNull(permissions);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.discovery = Objects.requireNonNull(discovery);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    private UUID authorizeActor(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken auth) || !auth.isAuthenticated()
                || !"corvex-auth".equals(auth.getToken().getClaimAsString("iss"))
                || auth.getToken().getExpiresAt() == null
                || !clock.instant().isBefore(auth.getToken().getExpiresAt())
                || (auth.getToken().getNotBefore() != null && clock.instant().isBefore(auth.getToken().getNotBefore()))
                || auth.getAuthorities().stream().noneMatch(a -> "ADMIN_USERS_MANAGE".equals(a.getAuthority()))) {
            throw new SecurityException("Provisioning denied");
        }
        final UUID actorId;
        try { actorId = UUID.fromString(auth.getToken().getSubject()); }
        catch (IllegalArgumentException | NullPointerException e) { throw new SecurityException("Provisioning denied"); }
        var actor = users.findById(actorId).orElseThrow(() -> new SecurityException("Provisioning denied"));
        if (!actorId.equals(actor.id()) || !actor.active() || !permissions.canManageUsers(actorId)) {
            throw new SecurityException("Provisioning denied");
        }
        return actorId;
    }

    private void requireActiveOwner(UUID ownerId) {
        var owner = users.findById(ownerId).orElseThrow(() -> new SecurityException("Provisioning denied"));
        if (!ownerId.equals(owner.id()) || !owner.active()) throw new SecurityException("Provisioning denied");
    }

    JamesProvisioningJournal.Intent create(Authentication authentication, UUID ownerId) throws IOException {
        Objects.requireNonNull(ownerId);
        UUID actorId = authorizeActor(authentication);
        requireActiveOwner(ownerId);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        java.util.Arrays.fill(bytes, (byte) 0);
        // Password is intentionally not returned or persisted. Native protocols
        // remain disabled; enabling them later requires a separate credential flow.
        return coordinator.create(ownerId, actorId, password);
    }

    /** Operation ID is supplied by caller; login/owner come only from the journal. */
    void discover(Authentication authentication, UUID operationId)
            throws IOException, GeneralSecurityException, InterruptedException {
        Objects.requireNonNull(operationId);
        UUID actorId = authorizeActor(authentication);
        var intent = discovery.requireCreated(operationId, actorId);
        requireActiveOwner(intent.ownerId());
        discovery.complete(intent);
    }
}
