package ru.corvex.mailstorage.james;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * Experimental internal orchestration, NOT a Spring bean or public endpoint.
 * The caller must authenticate and authorize the actor, verify that the owner
 * is current and active, generate a dedicated high-entropy password, and
 * establish a protected WebAdmin channel. This class does not enable a binding,
 * discover an account ID, configure delivery, or implement crash recovery.
 */
final class JamesProvisioningCoordinator {
    private final JamesProvisioningJournal journal;
    private final JamesWebAdminCreateClient admin;

    JamesProvisioningCoordinator(JamesProvisioningJournal journal, JamesWebAdminCreateClient admin) {
        this.journal = Objects.requireNonNull(journal);
        this.admin = Objects.requireNonNull(admin);
    }

    JamesProvisioningJournal.OperationStatus status(UUID operationId, UUID actorId) {
        return journal.findStatus(operationId, actorId)
                .orElseThrow(() -> new SecurityException("Provisioning denied"));
    }

    /**
     * Exactly one application-level create attempt, preceded by a committed
     * intent and submitting state. A positive result means only that James
     * returned 204 and the journal recorded it, not that a mailbox is usable.
     * Every ambiguous or non-204 response becomes uncertain, not a retry.
     */
    JamesProvisioningJournal.Intent create(UUID owner, UUID actor, String generatedPassword) throws IOException {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(actor);
        if (generatedPassword == null || !generatedPassword.matches("[A-Za-z0-9_-]{32,256}")) {
            throw new IllegalArgumentException("Invalid generated account password");
        }
        var intent = journal.reserve(owner, actor);
        var submission = journal.beginCreate(intent);
        final boolean created;
        try {
            created = admin.create(intent.login(), generatedPassword);
        } catch (IOException | RuntimeException failure) {
            // Failure to record uncertainty leaves the durable submitting
            // state, which is also blocked and must not be retried. Do not
            // propagate upstream errors which might include credentials.
            try { journal.markUncertain(intent); }
            catch (RuntimeException ignored) { /* submitting remains blocked */ }
            throw new JamesProvisioningUncertainException(intent.operationId());
        }
        if (!created) {
            try { journal.markUncertain(intent); }
            catch (RuntimeException ignored) { /* submitting remains blocked */ }
            throw new JamesProvisioningUncertainException(intent.operationId());
        }
        // A database commit error can itself have an unknown outcome. Preserve
        // the operation handle; never assume that repeating creation is safe.
        try { journal.confirmCreate(submission); }
        catch (RuntimeException ignored) { throw new JamesProvisioningUncertainException(intent.operationId()); }
        return intent;
    }
}
