package ru.corvex.mailstorage.james;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Experimental delivery intent, not wired into the pipeline. The caller must
 * independently authenticate the recipient and verify the exact immutable bytes
 * and AV decision. A committed submitting state always blocks replay, including
 * after a process crash or an ambiguous database/network response. Confirmation
 * is evidence only if the transport can prove the remote response belongs to
 * this submission. This class alone does not authorize spool deletion.
 */
final class JamesDeliveryJournal {
    record Intent(UUID operationId, String rawRef, UUID ownerId, String accountId,
                  String folderId, String sha256, long sizeBytes) { }
    record Reservation(Intent intent, String phase) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    JamesDeliveryJournal(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** An existing (rawRef, owner) must match every immutable input exactly. */
    Reservation reserve(String rawRef, UUID owner, String account, String folder, String hash, long size) {
        validText(rawRef); Objects.requireNonNull(owner); validText(account); validText(folder);
        if (hash == null || !hash.matches("[0-9a-f]{64}") || size < 0) throw new IllegalArgumentException("Invalid delivery digest");
        return Objects.requireNonNull(transaction.execute(status -> {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO corvex_james_delivery_intent "
                    + "(operation_id, raw_ref, owner_id, james_account_id, target_folder_id, sha256, size_bytes, phase) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 'reserved') ON CONFLICT (raw_ref, owner_id) DO NOTHING",
                    id, rawRef, owner, account, folder, hash, size);
            var rows = jdbc.query("SELECT operation_id, james_account_id, target_folder_id, sha256, size_bytes, phase "
                            + "FROM corvex_james_delivery_intent WHERE raw_ref = ? AND owner_id = ?",
                    (rs, n) -> new Reservation(new Intent(rs.getObject("operation_id", UUID.class), rawRef, owner,
                            rs.getString("james_account_id"), rs.getString("target_folder_id"),
                            rs.getString("sha256"), rs.getLong("size_bytes")), rs.getString("phase")), rawRef, owner);
            if (rows.size() != 1) throw new IllegalStateException("Delivery reservation unavailable");
            var result = rows.get(0);
            var existing = result.intent();
            if (!account.equals(existing.accountId()) || !folder.equals(existing.folderId())
                    || !hash.equals(existing.sha256()) || size != existing.sizeBytes()) {
                throw new IllegalStateException("Delivery reservation conflicts with immutable input");
            }
            return result;
        }));
    }

    /** Process-local capability: impossible to reconstruct from journal state. */
    static final class Submission {
        private final JamesDeliveryJournal issuer;
        private final Intent intent;
        private final AtomicBoolean used = new AtomicBoolean();
        private Submission(JamesDeliveryJournal issuer, Intent intent) { this.issuer = issuer; this.intent = intent; }
    }

    Submission begin(Intent intent) {
        validate(intent);
        transition(intent, "reserved", "submitting", null);
        return new Submission(this, intent);
    }

    void confirm(Submission submission, String remoteId) {
        validText(remoteId);
        consume(submission);
        transition(submission.intent, "submitting", "confirmed", remoteId);
    }

    void uncertain(Submission submission) {
        consume(submission);
        transition(submission.intent, "submitting", "uncertain", null);
    }

    private void consume(Submission submission) {
        if (submission == null || submission.issuer != this || !submission.used.compareAndSet(false, true)) {
            throw new SecurityException("Invalid delivery submission");
        }
    }

    private void transition(Intent intent, String from, String to, String remoteId) {
        validate(intent);
        transaction.executeWithoutResult(status -> {
            int count = jdbc.update("UPDATE corvex_james_delivery_intent SET phase = ?, remote_id = ?, updated_at = now() "
                            + "WHERE operation_id = ? AND raw_ref = ? AND owner_id = ? AND james_account_id = ? "
                            + "AND target_folder_id = ? AND sha256 = ? AND size_bytes = ? AND phase = ?",
                    to, remoteId, intent.operationId(), intent.rawRef(), intent.ownerId(), intent.accountId(),
                    intent.folderId(), intent.sha256(), intent.sizeBytes(), from);
            if (count != 1) throw new IllegalStateException("Delivery operation is not in the expected state");
        });
    }

    private static void validate(Intent intent) {
        Objects.requireNonNull(intent); Objects.requireNonNull(intent.operationId()); Objects.requireNonNull(intent.ownerId());
        validText(intent.rawRef()); validText(intent.accountId()); validText(intent.folderId());
        if (intent.sha256() == null || !intent.sha256().matches("[0-9a-f]{64}") || intent.sizeBytes() < 0) {
            throw new IllegalArgumentException("Invalid delivery intent");
        }
    }

    private static void validText(String value) {
        if (value == null || value.isBlank() || value.length() > 1024) throw new IllegalArgumentException("Invalid delivery value");
    }
}
