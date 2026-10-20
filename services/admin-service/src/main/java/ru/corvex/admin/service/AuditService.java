package ru.corvex.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.corvex.admin.api.dto.AdminDtos.AuditView;
import ru.corvex.admin.api.dto.AdminDtos.PageResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Журнал аудита (admin_db.audit_log) через JdbcTemplate: details — JSONB, JPA здесь
 * не нужен. Запись аудита не должна ронять основное действие: ошибки записи логируются.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public record Actor(UUID id, String name, String ip) {
        public static Actor system() {
            return new Actor(null, "system", null);
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Actor actor, String action, String entity, String entityId, Map<String, Object> details) {
        try {
            String json = objectMapper.writeValueAsString(details == null ? Map.of() : details);
            jdbc.update("""
                    INSERT INTO audit_log(actor_id, actor_name, action, entity, entity_id, details, ip_address)
                    VALUES (CAST(? AS uuid), ?, ?, ?, ?, CAST(? AS jsonb), ?)
                    """,
                    actor == null || actor.id() == null ? null : actor.id().toString(),
                    actor == null ? null : actor.name(),
                    action, entity, entityId, json,
                    actor == null ? null : actor.ip());
        } catch (Exception e) {
            log.error("Failed to write audit entry action={} entity={}#{}: {}", action, entity, entityId, e.getMessage());
        }
    }

    public PageResult<AuditView> query(UUID actorId, String action, Instant from, Instant to, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (actorId != null) {
            where.append(" AND actor_id = ?");
            args.add(actorId);
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND action = ?");
            args.add(action.trim());
        }
        if (from != null) {
            where.append(" AND created_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND created_at <= ?");
            args.add(Timestamp.from(to));
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_log" + where, Long.class, args.toArray());
        long totalElements = total == null ? 0 : total;
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);
        List<AuditView> rows = jdbc.query("""
                SELECT id, actor_id, actor_name, action, entity, entity_id, details::text AS details, ip_address, created_at
                FROM audit_log""" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new AuditView(
                        rs.getLong("id"),
                        rs.getString("actor_id") == null ? null : UUID.fromString(rs.getString("actor_id")),
                        rs.getString("actor_name"),
                        rs.getString("action"),
                        rs.getString("entity"),
                        rs.getString("entity_id"),
                        parseDetails(rs.getString("details")),
                        rs.getString("ip_address"),
                        rs.getTimestamp("created_at").toInstant()),
                pageArgs.toArray());
        int totalPages = safeSize == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);
        return new PageResult<>(rows, safePage, safeSize, totalElements, totalPages);
    }

    private Map<String, Object> parseDetails(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }
}
