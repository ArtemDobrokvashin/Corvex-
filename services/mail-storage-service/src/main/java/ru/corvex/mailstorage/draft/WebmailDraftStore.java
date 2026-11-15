package ru.corvex.mailstorage.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owner-scoped drafts; optimistic revisions prevent silent loss across tabs. */
@Service
public class WebmailDraftStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public WebmailDraftStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public record Draft(UUID id, long revision, DraftPayload payload, Instant updatedAt) { }

    @Transactional
    public Draft create(UUID owner, DraftPayload payload) {
        String encoded = encode(payload);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into webmail_drafts (id, owner_id, revision, payload) values (?, ?, 1, cast(? as jsonb))", id, owner, encoded);
        return get(owner, id);
    }

    @Transactional
    public Draft update(UUID owner, UUID id, long revision, DraftPayload payload) {
        if (revision < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная версия черновика");
        String encoded = encode(payload);
        int updated = jdbc.update("""
                update webmail_drafts set payload = cast(? as jsonb), revision = revision + 1, updated_at = now()
                where id = ? and owner_id = ? and revision = ?
                """, encoded, id, owner, revision);
        if (updated == 0) {
            // Only query the current owner's row; no disclosure of other users' drafts.
            get(owner, id);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Черновик был изменён в другой вкладке");
        }
        return get(owner, id);
    }

    /** Newest first, capped; never crosses the owner boundary. */
    @Transactional(readOnly = true)
    public List<Draft> list(UUID owner, int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return jdbc.query("""
                select id, revision, payload::text, updated_at from webmail_drafts
                where owner_id = ? order by updated_at desc limit ?
                """,
                (rs, row) -> new Draft(rs.getObject(1, UUID.class), rs.getLong(2), decode(rs.getString(3)),
                        rs.getTimestamp(4).toInstant()), owner, bounded);
    }

    @Transactional(readOnly = true)
    public Draft get(UUID owner, UUID id) {
        List<Draft> result = jdbc.query("select id, revision, payload::text, updated_at from webmail_drafts where owner_id = ? and id = ?",
                (rs, row) -> new Draft(rs.getObject(1, UUID.class), rs.getLong(2), decode(rs.getString(3)), rs.getTimestamp(4).toInstant()), owner, id);
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Черновик не найден");
        return result.get(0);
    }

    @Transactional
    public void delete(UUID owner, UUID id, long revision) {
        if (revision < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная версия черновика");
        int deleted = jdbc.update("delete from webmail_drafts where owner_id = ? and id = ? and revision = ?", owner, id, revision);
        if (deleted == 0) { get(owner, id); throw new ResponseStatusException(HttpStatus.CONFLICT, "Черновик был изменён в другой вкладке"); }
    }

    private String encode(DraftPayload payload) {
        if (payload == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Черновик не задан");
        try { payload.validate(); return json.writeValueAsString(payload); }
        catch (IllegalArgumentException | JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный или слишком большой черновик", e);
        }
    }
    private DraftPayload decode(String value) {
        try { return json.readValue(value, DraftPayload.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Не удалось прочитать черновик", e); }
    }
}
