package ru.corvex.mailstorage.james;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Experimental, disconnected policy for binding a verified principal to a
 * provisioned James account. No JWT verification, repository, token issuance
 * or Spring wiring is provided here. Inputs MUST come from trusted server-side
 * sources, never request parameters or unverified JWT claims.
 */
public final class JamesMailboxBinding {
    private JamesMailboxBinding() { }

    /** Both IDs must be immutable owner IDs from the verified token and directory. */
    public record Principal(UUID verifiedOwnerId, Set<String> verifiedPermissions) {
        public Principal {
            Objects.requireNonNull(verifiedOwnerId);
            verifiedPermissions = Set.copyOf(verifiedPermissions);
        }
    }

    /** An active owner and mapping loaded from trusted, current server-side records. */
    public record ProvisionedMailbox(UUID ownerId, boolean active, String jamesLogin, String accountId) { }
    public record BoundMailbox(String jamesLogin, String accountId) { }

    /**
     * The JMAP session account must come from the session authenticated with a
     * token issued specifically for this mapping, not from the browser. This
     * method cannot itself establish those preconditions or prevent a stale
     * directory lookup: callers must fail closed on all upstream failures.
     */
    public static BoundMailbox requireOwnRead(Principal principal, ProvisionedMailbox mapping,
                                               String authenticatedPrimaryAccountId) {
        if (principal == null || mapping == null || !mapping.active()
                || !principal.verifiedPermissions().contains("MAIL_READ_OWN")
                || !principal.verifiedOwnerId().equals(mapping.ownerId())
                || blank(mapping.jamesLogin()) || blank(mapping.accountId())
                || !mapping.accountId().equals(authenticatedPrimaryAccountId)) {
            throw new SecurityException("James mailbox binding rejected");
        }
        return new BoundMailbox(mapping.jamesLogin(), mapping.accountId());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
