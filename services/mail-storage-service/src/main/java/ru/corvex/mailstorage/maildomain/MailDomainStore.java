package ru.corvex.mailstorage.maildomain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL-хранилище доменов. Состояние James меняется только через
 * compare-and-set: два администратора не могут одновременно отправить PUT в
 * WebAdmin, а исход потерянного ответа нельзя переписать на «создан».
 */
public class MailDomainStore {

    private static final int MAX_DETAIL = 512;

    private final JdbcTemplate jdbc;

    public MailDomainStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(UUID id, String domain, boolean enabled, String jamesState, Instant jamesRequestedAt,
                      Instant jamesConfirmedAt, UUID createdBy, Instant createdAt) { }

    public static class AlreadyExistsException extends RuntimeException {
        public AlreadyExistsException() {
            super("Домен уже добавлен");
        }
    }

    public Row create(String domain, UUID actor) {
        try {
            return jdbc.query("INSERT INTO mail_domains (domain, created_by) VALUES (?, ?) "
                            + "RETURNING id, domain, enabled, james_state, james_requested_at, "
                            + "james_confirmed_at, created_by, created_at",
                    (rs, index) -> map(rs), domain, actor).get(0);
        } catch (DuplicateKeyException e) {
            throw new AlreadyExistsException();
        }
    }

    public Optional<Row> find(String domain) {
        return jdbc.query("SELECT id, domain, enabled, james_state, james_requested_at, james_confirmed_at, "
                        + "created_by, created_at FROM mail_domains WHERE domain = ?",
                (rs, index) -> map(rs), domain).stream().findFirst();
    }

    public List<Row> list() {
        return jdbc.query("SELECT id, domain, enabled, james_state, james_requested_at, james_confirmed_at, "
                + "created_by, created_at FROM mail_domains ORDER BY domain", (rs, index) -> map(rs));
    }

    /**
     * Домены, которые сервер действительно принимает: включённые в реестре и
     * подтверждённые в James. Непроверенные DNS-записи сюда не входят: они
     * влияют на готовность, но не на факт существования домена.
     */
    public List<String> deliveryDomains() {
        return jdbc.queryForList("SELECT domain FROM mail_domains "
                + "WHERE enabled = TRUE AND james_state = 'CREATED' ORDER BY domain", String.class);
    }

    /** true только если строка была именно в одном из разрешённых состояний. */
    public boolean claimRequest(UUID id, List<String> allowedFrom) {
        if (allowedFrom.isEmpty()) {
            return false;
        }
        String placeholders = String.join(", ", allowedFrom.stream().map(state -> "?").toList());
        Object[] args = new Object[allowedFrom.size() + 1];
        args[0] = id;
        for (int i = 0; i < allowedFrom.size(); i++) {
            args[i + 1] = allowedFrom.get(i);
        }
        int changed = jdbc.update("UPDATE mail_domains SET james_state = 'REQUESTING', "
                + "james_requested_at = now(), updated_at = now() "
                + "WHERE id = ? AND james_state IN (" + placeholders + ")", args);
        return changed == 1;
    }

    /** Завершение запроса допустимо только из REQUESTING. */
    public boolean completeRequest(UUID id, String state, boolean confirmed) {
        int changed = jdbc.update("UPDATE mail_domains SET james_state = ?, "
                        + "james_confirmed_at = CASE WHEN ?::boolean THEN now() ELSE james_confirmed_at END, "
                        + "updated_at = now() WHERE id = ? AND james_state = 'REQUESTING'",
                state, confirmed, id);
        return changed == 1;
    }

    /** Понижение состояния только при потерянном/неизвестном исходе. */
    public boolean markUncertain(UUID id) {
        int changed = jdbc.update("UPDATE mail_domains SET james_state = 'UNCERTAIN', updated_at = now() "
                + "WHERE id = ? AND james_state IN ('REQUESTING', 'CREATED')", id);
        return changed == 1;
    }

    public void recordEvent(UUID id, String domain, UUID actor, String event, String detail) {
        jdbc.update("INSERT INTO mail_domain_events (domain_id, domain, actor_id, event, detail) "
                        + "VALUES (?, ?, ?, ?, ?)",
                id, domain, actor, event, truncate(detail));
    }

    /** Подтверждение наличия домена в James, полученное чтением (GET 204). */
    public boolean markPresent(UUID id) {
        // REQUESTING исключён: незавершённый PUT нельзя переписать в «создан»
        // результатом чужого чтения.
        return jdbc.update("UPDATE mail_domains SET james_state = 'CREATED', james_confirmed_at = now(), "
                + "updated_at = now() WHERE id = ? AND james_state IN "
                + "('NOT_REQUESTED', 'FAILED', 'UNCERTAIN', 'CREATED')", id) == 1;
    }

    public List<MailDomainVerifier.Check> checks(UUID id) {
        return jdbc.query("SELECT check_name, state, detail, observed_at FROM mail_domain_checks "
                        + "WHERE domain_id = ?", (rs, index) -> new MailDomainVerifier.Check(
                        MailDomainVerifier.CheckName.valueOf(rs.getString("check_name")),
                        MailDomainVerifier.CheckState.valueOf(rs.getString("state")),
                        rs.getString("detail"),
                        rs.getTimestamp("observed_at").toInstant()), id);
    }

    public void saveChecks(UUID id, List<MailDomainVerifier.Check> checks) {
        for (MailDomainVerifier.Check check : checks) {
            jdbc.update("INSERT INTO mail_domain_checks (domain_id, check_name, state, detail, observed_at) "
                            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (domain_id, check_name) DO UPDATE "
                            + "SET state = EXCLUDED.state, detail = EXCLUDED.detail, "
                            + "observed_at = EXCLUDED.observed_at",
                    id, check.name().name(), check.state().name(), truncate(check.detail()),
                    java.sql.Timestamp.from(check.observedAt()));
        }
    }

    public boolean delete(String domain) {
        return jdbc.update("DELETE FROM mail_domains WHERE domain = ?", domain) == 1;
    }

    private static Row map(ResultSet rs) throws SQLException {
        java.sql.Timestamp requested = rs.getTimestamp("james_requested_at");
        java.sql.Timestamp confirmed = rs.getTimestamp("james_confirmed_at");
        return new Row(rs.getObject("id", UUID.class), rs.getString("domain"), rs.getBoolean("enabled"),
                rs.getString("james_state"), requested == null ? null : requested.toInstant(),
                confirmed == null ? null : confirmed.toInstant(), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    private static String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);
    }
}
