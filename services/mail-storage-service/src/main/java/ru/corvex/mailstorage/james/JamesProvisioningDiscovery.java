package ru.corvex.mailstorage.james;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Disconnected internal step: only confirmed account creation can be discovered.
 * No endpoint, security filter, account activation or production configuration.
 * Caller must recheck current actor permission and both users' active status.
 */
final class JamesProvisioningDiscovery {
    private static final String MAIL = "urn:ietf:params:jmap:mail";
    private final JamesProvisioningJournal journal;
    private final JamesReadTokenIssuer issuer;
    private final JamesJmapReadClient client;

    JamesProvisioningDiscovery(JamesProvisioningJournal journal, JamesReadTokenIssuer issuer, JamesJmapReadClient client) {
        this.journal = Objects.requireNonNull(journal);
        this.issuer = Objects.requireNonNull(issuer);
        this.client = Objects.requireNonNull(client);
    }

    JamesProvisioningJournal.Intent requireCreated(UUID operationId, UUID actorId) {
        return journal.findCreated(operationId, actorId)
                .orElseThrow(() -> new SecurityException("Provisioning denied"));
    }

    void complete(JamesProvisioningJournal.Intent intent)
            throws IOException, GeneralSecurityException, InterruptedException {
        Objects.requireNonNull(intent);
        // Never mint a token for a login supplied by a caller. Re-load the
        // confirmed intent from the journal and require all fields to match.
        var stored = requireCreated(intent.operationId(), intent.actorId());
        if (!stored.equals(intent)) throw new SecurityException("Provisioning denied");
        var principal = new JamesMailboxBinding.Principal(stored.ownerId(), Set.of("MAIL_READ_OWN"));
        // Temporary capability for session discovery ONLY. Token is a full
        // James bearer; never return/log it, and do not reuse it for mail.
        var candidate = new JamesMailboxBinding.ProvisionedMailbox(stored.ownerId(), true, stored.login(), "pending");
        String token = issuer.issue(principal, candidate);
        var primary = client.session(token).path("primaryAccounts").path(MAIL);
        if (!primary.isTextual() || primary.textValue().isBlank() || primary.textValue().length() > 1024) {
            throw new IOException("Invalid James primary account");
        }
        // Unique constraints and state transition are one DB transaction;
        // the mapping is explicitly disabled. No automatic activation.
        journal.recordBinding(stored, primary.textValue());
    }
}
