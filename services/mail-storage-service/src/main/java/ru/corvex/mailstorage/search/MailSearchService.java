package ru.corvex.mailstorage.search;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Полнотекстовый поиск по индексу писем.
 *
 * Зачем сервер, а не фильтрация в браузере: клиент видит только загруженную
 * страницу (50 писем), поэтому письмо из прошлого года «не находилось» вовсе,
 * а внутри загруженных поиск не видел текст дальше первых строк превью.
 *
 * Ограничения, которые здесь зафиксированы:
 * - владелец — всегда из JWT, в запрос подставляется как фильтр, а не как пожелание;
 * - лимит результатов ограничен сверху: поиск не должен превращаться в выгрузку ящика;
 * - необязательные фильтры (`from:`, `is:unread`, `has:attachment`, `before:`,
 *   `after:`, `subject:`) разбираются здесь, чтобы клиент оставался простым;
 * - если Elasticsearch недоступен, вызывающий получает 503 и честно говорит
 *   «поиск временно недоступен», а не «писем не найдено».
 */
@Service
public class MailSearchService {

    private static final int MAX_LIMIT = 100;

    private final ElasticsearchOperations operations;

    public MailSearchService(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    /** Разобранный запрос: текст плюс необязательные фильтры из префиксов. */
    public record SearchQuery(String text, String from, String subject, Boolean unread,
                              Boolean hasAttachment, String before, String after) {
    }

    public record SearchHit(UUID messageId, UUID folderId, long uid, String sender, String subject,
                            String snippet, String internalDate) {
    }

    /**
     * Разбор строки поиска в стиле Gmail: `from:anna договор is:unread has:attachment after:2026-01-01`.
     * Неизвестные `ключ:значение` остаются частью текста — лучше найти их как текст,
     * чем молча потерять половину запроса.
     */
    public static SearchQuery parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        String from = null;
        String subject = null;
        Boolean unread = null;
        Boolean hasAttachment = null;
        String before = null;
        String after = null;
        StringBuilder rest = new StringBuilder();
        for (String token : text.split("\\s+")) {
            String lower = token.toLowerCase();
            if (lower.startsWith("from:") && token.length() > 5) {
                from = token.substring(5);
            } else if (lower.startsWith("subject:") && token.length() > 8) {
                subject = token.substring(8);
            } else if (lower.startsWith("to:") && token.length() > 3) {
                // Получатели индексируются вместе с отправителем; для поиска
                // по адресату достаточно текстового совпадения.
                rest.append(token.substring(3)).append(' ');
            } else if (lower.equals("is:unread")) {
                unread = true;
            } else if (lower.equals("is:read")) {
                unread = false;
            } else if (lower.equals("has:attachment")) {
                hasAttachment = true;
            } else if (lower.startsWith("before:") && token.length() > 7) {
                before = token.substring(7);
            } else if (lower.startsWith("after:") && token.length() > 6) {
                after = token.substring(6);
            } else {
                rest.append(token).append(' ');
            }
        }
        return new SearchQuery(rest.toString().trim(), from, subject, unread, hasAttachment, before, after);
    }

    /**
     * Размер страницы поиска: клиент может попросить сколько угодно, но больше
     * {@link #MAX_LIMIT} за один запрос не уходит. Отдельный метод, потому что
     * именно это правило и надо проверять тестом — без моков Elasticsearch.
     */
    static int pageSize(int requested) {
        return Math.max(1, Math.min(requested, MAX_LIMIT));
    }

    public List<SearchHit> search(UUID ownerId, String rawQuery, int limit) {
        SearchQuery query = parse(rawQuery);
        int size = pageSize(limit);

        List<Query> filters = new java.util.ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t.field("ownerId").value(ownerId.toString()))));
        if (query.from() != null) {
            filters.add(Query.of(q -> q.match(m -> m.field("sender").query(query.from()))));
        }
        if (query.subject() != null) {
            filters.add(Query.of(q -> q.match(m -> m.field("subject").query(query.subject()))));
        }
        if (query.hasAttachment() != null) {
            filters.add(Query.of(q -> q.term(t -> t.field("hasAttachments").value(query.hasAttachment()))));
        }
        if (query.before() != null) {
            filters.add(Query.of(q -> q.range(r -> r.date(d -> d.field("internalDate").lt(query.before() + "T00:00:00Z")))));
        }
        if (query.after() != null) {
            filters.add(Query.of(q -> q.range(r -> r.date(d -> d.field("internalDate").gt(query.after() + "T00:00:00Z")))));
        }
        if (query.unread() != null) {
            // Прочитанность меняется чаще, чем индекс: значение берётся из
            // индексированного снимка, а точное состояние показывает список.
            filters.add(Query.of(q -> q.term(t -> t.field("seen").value(!query.unread()))));
        }

        Query text = query.text().isBlank()
                ? Query.of(q -> q.matchAll(m -> m))
                : Query.of(q -> q.multiMatch(m -> m
                        .query(query.text())
                        .fields("subject^2", "sender", "recipients", "body")));
        Query combined = Query.of(q -> q.bool(b -> b.must(text).filter(filters)));

        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(combined)
                .withMaxResults(size)
                .build();
        SearchHits<MessageSearchDocument> hits = operations.search(nativeQuery, MessageSearchDocument.class);
        return hits.getSearchHits().stream()
                .map(hit -> {
                    MessageSearchDocument doc = hit.getContent();
                    UUID messageId;
                    try {
                        messageId = UUID.fromString(doc.getId());
                    } catch (RuntimeException e) {
                        return null;
                    }
                    return new SearchHit(
                            messageId,
                            doc.getFolderId() == null ? null : UUID.fromString(doc.getFolderId()),
                            doc.getUid(),
                            doc.getSender(),
                            doc.getSubject(),
                            snippet(doc.getBody()),
                            doc.getInternalDate() == null ? null : doc.getInternalDate().toString());
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** Короткий фрагмент вокруг совпадения: в списке показывается превью, а не всё тело. */
    public static String snippet(String body) {
        if (body == null) return "";
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 180 ? flat : flat.substring(0, 180) + "…";
    }

    /** Термины, которые не должны превращаться в регулярное выражение при подсветке. */
    public static Set<String> tokens(String query) {
        return Set.of(query == null ? new String[0] : query.trim().split("\\s+"));
    }
}
