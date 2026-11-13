package ru.corvex.mailstorage.james;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Experimental durable intent for creation in the dedicated laptop James.
 * Not a bean, endpoint, provisioner, authorization check or ownership proof.
 * Caller MUST authenticate and authorize the actor, verify ACTIVE owner, and
 * exclusively control account creation. Never accept a browser-supplied login.
 * A created account is not evidence of ownership if the create response was
 * lost; submitting/uncertain operations are permanently blocked here.
 */
final class JamesProvisioningJournal {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    JamesProvisioningJournal(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    record Intent(UUID operationId, UUID ownerId, UUID actorId, String login) { }

    /** Creates a unique opaque account name unrelated to the user's email. */
    Intent reserve(UUID owner, UUID actor) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(actor);
        UUID operation = UUID.randomUUID();
        String login = "cvx-" + UUID.randomUUID().toString().replace("-", "") + "@localhost";
        Intent intent = new Intent(operation, owner, actor, login);
        transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO corvex_james_provisioning (operation_id, owner_id, actor_id, james_login, phase) VALUES (?, ?, ?, ?, 'reserved')",
                    operation, owner, actor, login);
            event(intent, "reserved");
        });
        return intent;
    }

    /** In-memory capability is intentionally lost on restart, never serialized. */
    static final class Submission {
        private final JamesProvisioningJournal issuer;
        private final Intent intent;
        private Submission(JamesProvisioningJournal issuer, Intent intent) {
            this.issuer = issuer;
            this.intent = intent;
        }
    }

    /** Must commit before the external create call; no automatic retries. */
    Submission beginCreate(Intent intent) {
        transition(intent, "reserved", "submitting");
        return new Submission(this, intent);
    }

    /** Only the process that submitted may confirm its unambiguous success. */
    void confirmCreate(Submission submission) {
        if (submission == null || submission.issuer != this) throw new SecurityException("Invalid creation submission");
        transition(submission.intent, "submitting", "created");
    }

    /** On timeout, transport error or restart with submitting, fail closed. */
    void markUncertain(Intent intent) { transition(intent, "submitting", "uncertain"); }

    /** Load only a confirmed creation belonging to the original actor. */
    Optional<Intent> findCreated(UUID operationId, UUID actorId) {
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(actorId);
        var rows = jdbc.query("SELECT operation_id, owner_id, actor_id, james_login FROM corvex_james_provisioning "
                        + "WHERE operation_id = ? AND actor_id = ? AND phase = 'created'",
                (rs, index) -> new Intent(rs.getObject("operation_id", UUID.class),
                        rs.getObject("owner_id", UUID.class), rs.getObject("actor_id", UUID.class),
                        rs.getString("james_login")), operationId, actorId);
        if (rows.size() > 1) throw new IllegalStateException("Duplicate provisioning operation");
        if (rows.isEmpty()) return Optional.empty();
        validate(rows.get(0));
        return Optional.of(rows.get(0));
    }

    /**
     * Only after successful creation and authenticated discovery of primary
     * account for that exact login. Mapping is deliberately disabled; an
     * independently authorized activation is not implemented here.
     */
    void recordBinding(Intent intent, String accountId) {
        validate(intent);
        if (accountId == null || accountId.isBlank() || accountId.length() > 1024) {
            throw new IllegalArgumentException("Invalid James account ID");
        }
        transaction.executeWithoutResult(status -> {
            advance(intent, "created", "bound");
            jdbc.update("INSERT INTO corvex_james_mailbox_binding (owner_id, james_login, account_id, enabled) VALUES (?, ?, ?, false)",
                    intent.ownerId(), intent.login(), accountId);
            event(intent, "bound");
        });
    }

    private void transition(Intent intent, String from, String to) {
        validate(intent);
        transaction.executeWithoutResult(status -> {
            advance(intent, from, to);
            event(intent, to);
        });
    }

    private void advance(Intent intent, String from, String to) {
        int changed = jdbc.update("UPDATE corvex_james_provisioning SET phase = ?, updated_at = now() "
                        + "WHERE operation_id = ? AND owner_id = ? AND actor_id = ? AND james_login = ? AND phase = ?",
                to, intent.operationId(), intent.ownerId(), intent.actorId(), intent.login(), from);
        if (changed != 1) throw new IllegalStateException("Provisioning operation is not in the expected state");
    }

    private void event(Intent intent, String phase) {
        jdbc.update("INSERT INTO corvex_james_provisioning_event (operation_id, actor_id, event) VALUES (?, ?, ?)",
                intent.operationId(), intent.actorId(), phase);
    }

    private static void validate(Intent intent) {
        Objects.requireNonNull(intent);
        Objects.requireNonNull(intent.operationId());
        Objects.requireNonNull(intent.ownerId());
        Objects.requireNonNull(intent.actorId());
        if (intent.login() == null || !intent.login().matches("cvx-[0-9a-f]{32}@localhost")) {
            throw new IllegalArgumentException("Invalid generated James login");
        }
    }
}
