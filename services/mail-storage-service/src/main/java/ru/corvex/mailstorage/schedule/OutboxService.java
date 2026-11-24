package ru.corvex.mailstorage.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.corvex.mailstorage.pipeline.MailEventPublisher;
import ru.corvex.mailstorage.pipeline.MailPipelineEvents;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Отложенная отправка («Отправить позже»).
 *
 * <p>Письмо собирается в MIME сразу при планировании и кладётся в спул: через
 * час у человека может не быть сессии, а права и список получателей должны
 * остаться теми же, что при нажатии кнопки. Планировщик
 * ({@link ScheduledSendScheduler}) в срок публикует те же события, что и
 * обычная отправка, поэтому путь до SMTP один и тот же.
 *
 * <p>Отменить можно только ещё не отправленное письмо. Это честно сказано и в
 * интерфейсе, и здесь: после перевода в SENDING отмена возвращает false.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);
    /** Дальше года письмо не откладывается: это почти наверняка опечатка в дате. */
    static final Duration MAX_HORIZON = Duration.ofDays(366);
    /** Сколько ждём перед повтором после неудачной попытки (SMTP недоступен). */
    static final Duration RETRY_DELAY = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final RawMessageSpool spool;
    private final MailEventPublisher events;

    public OutboxService(JdbcTemplate jdbc, RawMessageSpool spool, MailEventPublisher events) {
        this.jdbc = jdbc;
        this.spool = spool;
        this.events = events;
    }

    /**
     * Кладёт письмо в очередь на будущее. Возвращает идентификатор записи:
     * по нему отменяют отправку.
     */
    @Transactional
    public UUID schedule(UUID ownerId, RawMessageSpool.StoredRawMessage raw, List<String> local,
                         List<String> external, String fromAddress, String messageIdHeader,
                         UUID externalAccountId, String subject, boolean saveToSent, Instant sendAt) {
        Instant now = Instant.now();
        if (sendAt == null || !sendAt.isAfter(now)) {
            throw new IllegalArgumentException("Время отправки уже прошло");
        }
        if (sendAt.isAfter(now.plus(MAX_HORIZON))) {
            throw new IllegalArgumentException("Отправить можно не позже чем через год");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into webmail_outbox (id, owner_id, raw_ref, local_recipients, external_recipients,
                                            from_address, message_id_header, external_account_id,
                                            subject, save_to_sent, send_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, ownerId, raw.rawRef(), join(local), join(external), fromAddress, messageIdHeader,
                externalAccountId, subject, saveToSent, Timestamp.from(sendAt));
        return id;
    }

    /**
     * Отмена. Возвращает false, если письмо уже отправляется или ушло: это не
     * ошибка клиента, а честный ответ «уже поздно».
     */
    @Transactional
    public boolean cancel(UUID ownerId, UUID id) {
        List<String> refs = jdbc.query("""
                select raw_ref from webmail_outbox
                where id = ? and owner_id = ? and status = 'SCHEDULED'
                """, (rs, row) -> rs.getString(1), id, ownerId);
        int updated = jdbc.update("""
                update webmail_outbox set status = 'CANCELLED', updated_at = now()
                where id = ? and owner_id = ? and status = 'SCHEDULED'
                """, id, ownerId);
        if (updated == 0) {
            return false;
        }
        for (String rawRef : refs) {
            try {
                spool.delete(rawRef);
            } catch (Exception e) {
                // Спул — временное хранилище: осиротевший файл подчистит ретеншн,
                // а отменённая отправка важнее уборки.
                log.warn("Не удалось удалить спул отменённого письма {}: {}", rawRef, e.getMessage());
            }
        }
        return true;
    }

    /** Запланированные письма владельца — чтобы показать их с пометкой «Отправится позже». */
    @Transactional(readOnly = true)
    public List<ScheduledSend> list(UUID ownerId) {
        return jdbc.query("""
                select id, send_at, status from webmail_outbox
                where owner_id = ? and status in ('SCHEDULED','SENDING','FAILED')
                order by send_at
                """,
                (rs, row) -> new ScheduledSend(rs.getObject(1, UUID.class),
                        rs.getTimestamp(2).toInstant(), rs.getString(3)),
                ownerId);
    }

    /**
     * Отправляет одно наступившее письмо. Вызывается планировщиком по одной
     * записи, чтобы сбой SMTP на одном письме не задерживал остальные.
     */
    @Transactional
    public void dispatch(UUID id) {
        List<ScheduledRow> rows = jdbc.query("""
                select id, owner_id, raw_ref, local_recipients, external_recipients, from_address,
                       message_id_header, external_account_id, attempts, subject, save_to_sent
                from webmail_outbox where id = ? and status in ('SCHEDULED','FAILED')
                """,
                (rs, row) -> new ScheduledRow(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        split(rs.getString(4)), split(rs.getString(5)), rs.getString(6), rs.getString(7),
                        rs.getObject(8, UUID.class), rs.getInt(9), rs.getString(10), rs.getBoolean(11)),
                id);
        if (rows.isEmpty()) {
            return;
        }
        ScheduledRow row = rows.get(0);
        if (!spool.exists(row.rawRef())) {
            jdbc.update("""
                    update webmail_outbox set status = 'FAILED', last_error = ?, updated_at = now()
                    where id = ?
                    """, "Собранное письмо больше не найдено в хранилище", id);
            return;
        }
        jdbc.update("update webmail_outbox set status = 'SENDING', updated_at = now() where id = ?", id);

        Instant now = Instant.now();
        try {
            for (String recipient : row.local()) {
                events.publishConfirmed("mail.inbound.received", new MailPipelineEvents.InboundReceivedEvent(
                        row.rawRef(), row.fromAddress(), List.of(recipient), "webmail",
                        sizeOrZero(row.rawRef()), null, now), row.rawRef());
            }
            if (!row.external().isEmpty()) {
                if (row.externalAccountId() != null) {
                    events.publishConfirmed("mail.outbound.external.queued",
                            new MailPipelineEvents.ExternalOutboundQueuedEvent(
                                    row.rawRef(), row.externalAccountId(), row.fromAddress(), row.external(),
                                    row.messageIdHeader(), row.ownerId(), now, 1), row.rawRef());
                } else {
                    events.publishConfirmed("mail.outbound.queued",
                            new MailPipelineEvents.OutboundQueuedEvent(
                                    row.rawRef(), row.fromAddress(), row.external(),
                                    row.messageIdHeader(), row.ownerId(), now, 1), row.rawRef());
                }
            }
            jdbc.update("update webmail_outbox set status = 'SENT', updated_at = now() where id = ?", id);
        } catch (RuntimeException e) {
            int attempts = row.attempts() + 1;
            String status = attempts >= MAX_ATTEMPTS ? "FAILED" : "SCHEDULED";
            Instant retryAt = now.plus(RETRY_DELAY.multipliedBy(attempts));
            log.warn("Отложенная отправка {} не удалась (попытка {}): {}", id, attempts, e.getMessage());
            jdbc.update("""
                    update webmail_outbox set status = ?, attempts = ?, last_error = ?, send_at = ?, updated_at = now()
                    where id = ?
                    """, status, attempts, truncate(e.getMessage()), Timestamp.from(retryAt), id);
        }
    }

    /** Записи, которым пора уходить; каждая отправляется отдельной транзакцией. */
    @Transactional(readOnly = true)
    public List<UUID> dueIds(Instant now, int limit) {
        return jdbc.query("""
                select id from webmail_outbox
                where status = 'SCHEDULED' and send_at <= ?
                order by send_at limit ?
                """, (rs, row) -> rs.getObject(1, UUID.class), Timestamp.from(now), limit);
    }

    private long sizeOrZero(String rawRef) {
        try {
            return spool.size(rawRef);
        } catch (java.io.IOException e) {
            // Размер нужен только для учёта квоты получателя: само письмо уже
            // проверено на существование, и отправка важнее точной цифры.
            return 0;
        }
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join("\n", values);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("\n")).filter(part -> !part.isBlank()).toList();
    }

    public record ScheduledSend(UUID id, Instant sendAt, String status) {
    }

    private record ScheduledRow(UUID id, UUID ownerId, String rawRef, List<String> local,
                                List<String> external, String fromAddress, String messageIdHeader,
                                UUID externalAccountId, int attempts, String subject, boolean saveToSent) {
    }
}
