package ru.corvex.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import ru.corvex.admin.api.dto.AdminDtos.AuditView;
import ru.corvex.admin.api.dto.AdminDtos.PageResult;
import ru.corvex.admin.service.AuditService.Actor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private JdbcTemplate jdbc;
    private AuditService service;
    private List<Object> updateArguments;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new AuditService(jdbc);
        updateArguments = new ArrayList<>();
    }

    @Test
    void recordWritesActorActionTargetAndJsonDetails() {
        captureUpdateArguments();

        service.record(new Actor(ACTOR_ID, "root", "192.0.2.10"),
                "USER_CREATED", "user", "ivan",
                Map.of("email", "ivan@example.test", "quotaBytes", 5000, "enabled", true));

        assertEquals(ACTOR_ID.toString(), updateArguments.get(0));
        assertEquals("root", updateArguments.get(1));
        assertEquals("USER_CREATED", updateArguments.get(2));
        assertEquals("user", updateArguments.get(3));
        assertEquals("ivan", updateArguments.get(4));
        assertTrue(updateArguments.get(5).toString().contains("ivan@example.test"));
        assertEquals("192.0.2.10", updateArguments.get(6));
    }

    @Test
    void recordUsesEmptyJsonForNullActorAndDetails() {
        captureUpdateArguments();

        service.record(null, "SYSTEM", null, null, null);

        assertNull(updateArguments.get(0));
        assertNull(updateArguments.get(1));
        assertEquals("SYSTEM", updateArguments.get(2));
        assertNull(updateArguments.get(3));
        assertNull(updateArguments.get(4));
        assertEquals("{}", updateArguments.get(5));
        assertNull(updateArguments.get(6));
    }

    @Test
    void recordSkipsInsertWhenDetailsCannotBeSerialized() {
        captureUpdateArguments();
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);

        assertDoesNotThrow(() -> service.record(Actor.system(), "TEST", "system", null, cyclic));
        assertTrue(updateArguments.isEmpty());
    }

    @Test
    void recordNeverPropagatesDatabaseFailure() {
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database is down"));

        assertDoesNotThrow(() -> service.record(
                Actor.system(), "BACKUP", "system", null, Map.of()));
    }

    @Test
    void systemActorIsStableAndHasNoNetworkAddress() {
        Actor actor = Actor.system();

        assertNull(actor.id());
        assertEquals("system", actor.name());
        assertNull(actor.ip());
        assertEquals(actor, Actor.system());
    }

    @Test
    void queryAppliesAllFiltersClampsPaginationAndParsesDetails() throws Exception {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T00:00:00Z");
        List<ResultSet> rows = List.of(
                row(1, "{\"text\":\"ok\",\"number\":42,\"flag\":true,\"empty\":null,"
                        + "\"object\":{\"a\":1},\"array\":[1,2]}"),
                row(2, "not-json"),
                row(3, null));
        QueryCapture capture = stubQuery(401L, rows);

        PageResult<AuditView> result = service.query(ACTOR_ID, "USER_CREATED", from, to, -5, 999);

        assertEquals(0, result.page());
        assertEquals(200, result.size());
        assertEquals(401, result.totalElements());
        assertEquals(3, result.totalPages());
        assertTrue(capture.countSql.contains("actor_id = ?"));
        assertTrue(capture.countSql.contains("action = ?"));
        assertTrue(capture.countSql.contains("created_at >= ?"));
        assertTrue(capture.countSql.contains("created_at <= ?"));
        assertEquals(List.of(ACTOR_ID, "USER_CREATED",
                Timestamp.from(from), Timestamp.from(to)), capture.countArgs);
        assertEquals(200, capture.pageArgs.get(capture.pageArgs.size() - 2));
        assertEquals(0L, capture.pageArgs.get(capture.pageArgs.size() - 1));

        AuditView first = result.content().get(0);
        assertEquals("ok", first.details().get("text"));
        assertEquals(42, first.details().get("number"));
        assertEquals(true, first.details().get("flag"));
        assertNull(first.details().get("empty"));
        assertEquals(Map.of("a", 1), first.details().get("object"));
        assertEquals(List.of(1, 2), first.details().get("array"));
        assertEquals(Map.of("raw", "not-json"), result.content().get(1).details());
        assertEquals(Map.of(), result.content().get(2).details());
    }

    @Test
    void queryWithoutFiltersHandlesNullCountAndRowWithoutActor() throws Exception {
        ResultSet row = row(9, "{}");
        when(row.getString("actor_id")).thenReturn(null);
        QueryCapture capture = stubQuery(null, List.of(row));

        PageResult<AuditView> result = service.query(null, null, null, null, 2, 10);

        assertEquals(0, result.totalElements());
        assertEquals(0, result.totalPages());
        assertEquals(2, result.page());
        assertEquals(10, result.size());
        assertNull(result.content().get(0).actorId());
        assertEquals(List.of(10, 20L), capture.pageArgs);
        assertTrue(capture.countArgs.isEmpty());
    }

    private void captureUpdateArguments() {
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            updateArguments.clear();
            updateArguments.addAll(flatten(invocation.getArguments(), 1));
            return 1;
        });
    }

    private QueryCapture stubQuery(Long total, List<ResultSet> rows) {
        QueryCapture capture = new QueryCapture();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    capture.countSql = invocation.getArgument(0);
                    capture.countArgs = flatten(invocation.getArguments(), 2);
                    return total;
                });
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<AuditView>>any(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    capture.pageSql = invocation.getArgument(0);
                    capture.pageArgs = flatten(invocation.getArguments(), 2);
                    RowMapper<AuditView> mapper = invocation.getArgument(1);
                    List<AuditView> result = new ArrayList<>();
                    for (int i = 0; i < rows.size(); i++) {
                        result.add(mapper.mapRow(rows.get(i), i));
                    }
                    return result;
                });
        return capture;
    }

    private ResultSet row(long id, String details) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(id);
        when(rs.getString("actor_id")).thenReturn(ACTOR_ID.toString());
        when(rs.getString("actor_name")).thenReturn("root");
        when(rs.getString("action")).thenReturn("USER_CREATED");
        when(rs.getString("entity")).thenReturn("user");
        when(rs.getString("entity_id")).thenReturn("ivan");
        when(rs.getString("details")).thenReturn(details);
        when(rs.getString("ip_address")).thenReturn("192.0.2.10");
        when(rs.getTimestamp("created_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-01-15T12:30:00Z")));
        return rs;
    }

    private List<Object> flatten(Object[] invocationArguments, int firstNonSqlArgument) {
        List<Object> result = new ArrayList<>();
        if (invocationArguments.length > firstNonSqlArgument
                && invocationArguments[firstNonSqlArgument] instanceof Object[] values) {
            result.addAll(Arrays.asList(values));
        } else {
            for (int i = firstNonSqlArgument; i < invocationArguments.length; i++) {
                result.add(invocationArguments[i]);
            }
        }
        return result;
    }

    private static final class QueryCapture {
        private String countSql;
        private String pageSql;
        private List<Object> countArgs = List.of();
        private List<Object> pageArgs = List.of();
    }
}
