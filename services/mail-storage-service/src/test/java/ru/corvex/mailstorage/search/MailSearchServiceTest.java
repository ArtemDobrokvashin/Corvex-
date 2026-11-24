package ru.corvex.mailstorage.search;

import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Серверный поиск: без него веб-почта искала только в загруженной странице
 * (50 писем) и только по превью. Здесь проверяется то, что видно человеку:
 * фильтры Gmail-стиля, ограничение выборки и фрагмент вместо всего тела.
 */
class MailSearchServiceTest {

    private final ElasticsearchOperations operations = mock(ElasticsearchOperations.class);

    @Test
    void parsesGmailStyleFiltersAndKeepsUnknownTokensAsText() {
        var query = MailSearchService.parse("договор from:anna is:unread has:attachment after:2026-01-01 before:2026-02-01 to:oleg");

        assertEquals("договор oleg", query.text());
        assertEquals("anna", query.from());
        assertEquals(Boolean.TRUE, query.unread());
        assertEquals(Boolean.TRUE, query.hasAttachment());
        assertEquals("2026-01-01", query.after());
        assertEquals("2026-02-01", query.before());
        assertNull(query.subject());
    }

    @Test
    void readsFilterWithoutValueAsPlainText() {
        // `from:` без значения — не фильтр: лучше искать это как текст,
        // чем потерять часть запроса.
        var query = MailSearchService.parse("from: договор");
        assertEquals("from: договор", query.text());
        assertNull(query.from());
    }

    @Test
    void limitsTheResultSizeSoSearchCannotDumpTheMailbox() {
        // Размер страницы читаем прямо в момент вызова: так проверка не зависит
        // от ArgumentCaptor и от того, какой перегруз search() выбрал Mockito.
        java.util.concurrent.atomic.AtomicInteger requested = new java.util.concurrent.atomic.AtomicInteger(-1);
        when(operations.search(any(org.springframework.data.elasticsearch.core.query.Query.class),
                org.mockito.ArgumentMatchers.eq(MessageSearchDocument.class)))
                .thenAnswer(invocation -> {
                    org.springframework.data.elasticsearch.core.query.Query query = invocation.getArgument(0);
                    requested.set(query.getMaxResults() == null ? -1 : query.getMaxResults());
                    return emptyHits();
                });

        new MailSearchService(operations).search(UUID.randomUUID(), "payment", 10_000);

        // 100 — потолок на страницу поиска, сколько бы ни просил клиент.
        assertEquals(100, requested.get());
    }

    @Test
    void mapsIndexDocumentsToResultViews() {
        MessageSearchDocument doc = new MessageSearchDocument(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                42L, "anna@company.local", List.of("me@company.local"), "Договор №118",
                "Подписанный скан во вложении. " + "Остальное — в письме. ".repeat(20),
                Instant.parse("2026-04-03T10:00:00Z"), true).withSeen(false);
        @SuppressWarnings("unchecked")
        SearchHit<MessageSearchDocument> hit = mock(SearchHit.class);
        when(hit.getContent()).thenReturn(doc);
        @SuppressWarnings("unchecked")
        SearchHits<MessageSearchDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of(hit));
        when(operations.search(any(org.springframework.data.elasticsearch.core.query.Query.class),
                org.mockito.ArgumentMatchers.eq(MessageSearchDocument.class)))
                .thenReturn(hits);

        var results = new MailSearchService(operations).search(UUID.randomUUID(), "договор", 25);

        assertEquals(1, results.size());
        assertEquals("Договор №118", results.get(0).subject());
        assertEquals(42L, results.get(0).uid());
        assertTrue(results.get(0).snippet().endsWith("…"));
        assertTrue(results.get(0).snippet().length() <= 181);
    }

    @Test
    void snippetCollapsesWhitespaceAndKeepsShortBodiesWhole() {
        assertEquals("Короткое письмо без переносов",
                MailSearchService.snippet("Короткое\n\n письмо\tбез переносов"));
        assertEquals("", MailSearchService.snippet(null));
    }

    @SuppressWarnings("unchecked")
    private static SearchHits<MessageSearchDocument> emptyHits() {
        SearchHits<MessageSearchDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of());
        return hits;
    }
}
