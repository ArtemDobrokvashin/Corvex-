package ru.corvex.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.corvex.admin.service.AuditService.Actor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Версионированный JSON-документ; *Enc поля содержат только AES-GCM ciphertext. */
@Repository
public class AlertConfigRepository {

    public record Config(
            int repeatNormalMinutes,
            int repeatCriticalMinutes,
            Map<String, Boolean> categories,
            boolean internalEmailEnabled,
            String internalRecipients,
            String internalFrom,
            boolean externalEmailEnabled,
            String externalEmailRecipients,
            String externalSmtpHost,
            int externalSmtpPort,
            String externalSmtpTlsMode,
            String externalSmtpUsername,
            String externalSmtpPasswordEnc,
            String externalSmtpFrom,
            boolean telegramEnabled,
            String telegramChatId,
            String telegramBotTokenEnc,
            boolean maxEnabled,
            String maxChatId,
            String maxUserId,
            String maxBotTokenEnc) {
    }

    public record Stored(Config config, long revision, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AlertConfigRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Stored> find() {
        List<Stored> result = jdbc.query("""
                SELECT config::text AS config, revision, updated_at
                FROM alert_runtime_config WHERE id = 1
                """, (rs, rowNum) -> new Stored(
                readConfig(rs.getString("config")),
                rs.getLong("revision"),
                rs.getTimestamp("updated_at").toInstant()));
        return result.stream().findFirst();
    }

    public Stored save(Config config, Actor actor) {
        final String json;
        try {
            json = mapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать alert-конфигурацию", e);
        }
        String actorId = actor == null || actor.id() == null ? null : actor.id().toString();
        return jdbc.queryForObject("""
                INSERT INTO alert_runtime_config(id, config, revision, updated_by, updated_at)
                VALUES (1, CAST(? AS jsonb), 1, CAST(? AS uuid), now())
                ON CONFLICT (id) DO UPDATE SET
                    config = EXCLUDED.config,
                    revision = alert_runtime_config.revision + 1,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = now()
                RETURNING revision, updated_at
                """, (rs, rowNum) -> new Stored(config, rs.getLong("revision"),
                rs.getTimestamp("updated_at").toInstant()), json, actorId);
    }

    private Config readConfig(String json) {
        try {
            return mapper.readValue(json, Config.class);
        } catch (Exception e) {
            throw new IllegalStateException("Повреждён сохранённый alert-конфиг", e);
        }
    }
}
