package ru.corvex.mailstorage.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Хранение настроек почты за пользователем, а не за браузером.
 *
 * <p>Почему это важно: подпись и порядок сортировки, застрявшие в
 * {@code localStorage}, не переезжают на второй компьютер, а подпись не
 * попадает в письма из Thunderbird. Здесь настройки общие для всех клиентов
 * одного ящика.
 *
 * <p>Мусор из запроса не сохраняется: неизвестные значения заменяются
 * умолчаниями, а не падают 500-й. Так старая вкладка с устаревшим набором
 * полей не ломает человеку вход.
 */
@Service
public class MailSettingsService {

    private static final Set<String> DENSITIES = Set.of("comfortable", "compact");
    private static final Set<String> MARK_READ = Set.of("automatic", "manual");
    private static final Set<String> SORTS = Set.of("date-desc", "date-asc", "sender-asc", "subject-asc", "unread-first");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MailSettingsService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public MailSettings get(UUID ownerId) {
        List<String> rows = jdbc.query("select payload::text from webmail_settings where owner_id = ?",
                (rs, row) -> rs.getString(1), ownerId);
        if (rows.isEmpty()) {
            return MailSettings.defaults();
        }
        try {
            return normalize(json.readValue(rows.get(0), MailSettings.class));
        } catch (JsonProcessingException e) {
            // Испорченная строка не должна запирать человеку почту.
            return MailSettings.defaults();
        }
    }

    @Transactional
    public MailSettings save(UUID ownerId, MailSettings incoming) {
        MailSettings settings = normalize(incoming == null ? MailSettings.defaults() : incoming);
        String encoded;
        try {
            encoded = json.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить настройки", e);
        }
        jdbc.update("""
                insert into webmail_settings (owner_id, payload, signature_text, signature_for_submission, updated_at)
                values (?, cast(? as jsonb), ?, ?, now())
                on conflict (owner_id) do update
                set payload = excluded.payload, signature_text = excluded.signature_text,
                    signature_for_submission = excluded.signature_for_submission, updated_at = now()
                """, ownerId, encoded, settings.signature(), settings.signatureForSubmission());
        return settings;
    }

    /**
     * Подпись для писем из SMTP submission. Пусто, если подписи нет или
     * человек не включал её для чужих клиентов: молча дописывать текст в
     * письмо, которое он набирал в Thunderbird, нельзя.
     */
    @Transactional(readOnly = true)
    public Optional<String> signatureFor(UUID ownerId) {
        return jdbc.query(
                "select signature_text, signature_for_submission from webmail_settings where owner_id = ?",
                (rs, row) -> rs.getBoolean(2) ? rs.getString(1) : null, ownerId).stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    static MailSettings normalize(MailSettings value) {
        MailSettings defaults = MailSettings.defaults();
        String signature = value.signature() == null ? "" : value.signature();
        if (signature.length() > MailSettings.MAX_SIGNATURE) {
            signature = signature.substring(0, MailSettings.MAX_SIGNATURE);
        }
        return new MailSettings(
                DENSITIES.contains(value.density()) ? value.density() : defaults.density(),
                value.showPreview(),
                value.conversations(),
                MARK_READ.contains(value.markRead()) ? value.markRead() : defaults.markRead(),
                SORTS.contains(value.defaultSort()) ? value.defaultSort() : defaults.defaultSort(),
                signature,
                value.signatureForNew(),
                value.signatureForReplies(),
                value.notifications(),
                value.signatureForSubmission());
    }
}
