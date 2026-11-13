package ru.corvex.mailstorage.james;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;

/** Experimental read-only lookup. Database errors propagate; no fallback. */
final class JdbcJamesMailboxRegistry implements JamesMailboxRegistry {
    private final JdbcTemplate jdbc;
    JdbcJamesMailboxRegistry(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public Optional<JamesMailboxBinding.ProvisionedMailbox> find(UUID ownerId) {
        Objects.requireNonNull(ownerId);
        var rows = jdbc.query("SELECT owner_id, enabled, james_login, account_id "
                        + "FROM corvex_james_mailbox_binding WHERE owner_id = ?",
                (rs, row) -> new JamesMailboxBinding.ProvisionedMailbox(
                        rs.getObject("owner_id", UUID.class), rs.getBoolean("enabled"),
                        rs.getString("james_login"), rs.getString("account_id")), ownerId);
        if (rows.size() > 1) throw new IllegalStateException("Duplicate James owner mapping");
        return rows.stream().findFirst();
    }
}
