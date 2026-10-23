package ru.corvex.mailimap.server;

import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.exception.UnsupportedCriteriaException;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.mailbox.model.ThreadId;
import org.junit.jupiter.api.Test;
import ru.corvex.mailimap.client.ImapStorageClient;

import javax.mail.Flags;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Полная матрица поддерживаемых SEARCH-критериев без обращения к IMAP-сокету. */
class CorvexSearchTest {

    private static final UUID MESSAGE_UUID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant INTERNAL_DATE = Instant.parse("2026-07-14T12:00:00Z");
    private static final byte[] RAW = (
            "From: petrov@corp.local\r\n"
                    + "To: ivanov@corp.local\r\n"
                    + "Subject: Raw subject\r\n"
                    + "Message-ID: <raw@corp.local>\r\n"
                    + "X-Project: Alpha\r\n"
                    + "X-Project: Beta\r\n"
                    + "\tGamma\r\n"
                    + "X-Empty: \r\n"
                    + "\r\n"
                    + "Needle in raw body").getBytes(StandardCharsets.ISO_8859_1);

    private final AtomicInteger rawLoads = new AtomicInteger();
    private final CorvexSearch.RawLoader rawLoader = meta -> {
        rawLoads.incrementAndGet();
        return RAW;
    };

    private static ImapStorageClient.MessageMeta meta() {
        return new ImapStorageClient.MessageMeta(MESSAGE_UUID, 7, 42, "<m1@corp.local>",
                "petrov@corp.local", "Квартальный отчёт", INTERNAL_DATE, 1_500,
                true, false, true, false, true);
    }

    @Test
    void emptyAllAndLogicalConjunctionsFollowJamesSemantics() throws Exception {
        var meta = meta();
        assertTrue(CorvexSearch.matches(SearchQuery.matchAll(), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(SearchQuery.all()), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(SearchQuery.and(
                SearchQuery.flagIsSet(Flags.Flag.SEEN),
                SearchQuery.subject("отчёт"))), meta, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(SearchQuery.and(
                SearchQuery.flagIsSet(Flags.Flag.SEEN),
                SearchQuery.subject("договор"))), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(SearchQuery.or(
                SearchQuery.subject("договор"),
                SearchQuery.flagIsSet(Flags.Flag.FLAGGED))), meta, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(SearchQuery.or(
                SearchQuery.subject("договор"),
                SearchQuery.flagIsSet(Flags.Flag.ANSWERED))), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(SearchQuery.not(List.of(
                SearchQuery.subject("договор"),
                SearchQuery.flagIsSet(Flags.Flag.ANSWERED)))), meta, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(SearchQuery.not(
                SearchQuery.flagIsSet(Flags.Flag.SEEN))), meta, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.all(), SearchQuery.subject("отсутствует")), meta, rawLoader),
                "критерии верхнего уровня объединяются AND");
    }

    @Test
    void flagsCustomKeywordsUidSizeAndModseqUseMetadata() throws Exception {
        var meta = meta();
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.flag(Flags.Flag.SEEN, true)), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.flagSet(Flags.Flag.ANSWERED, false)), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.flagIsSet(Flags.Flag.FLAGGED)), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.flagIsUnSet(Flags.Flag.DRAFT)), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.flagIsSet(Flags.Flag.DELETED)), meta, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.flagIsSet(Flags.Flag.RECENT)), meta, rawLoader),
                "для уже прочитанного письма приближённый \\Recent снят");
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.flagIsUnSet(Flags.Flag.USER)), meta, rawLoader),
                "неизвестный системный флаг USER не считается установленным");

        assertFalse(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.flagIsSet("$ProjectX")), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.flagIsUnSet("$ProjectX")), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.flagSet("$ProjectX", false)), meta, rawLoader));

        assertTrue(CorvexSearch.matches(SearchQuery.of(SearchQuery.uid(
                new SearchQuery.UidRange(MessageUid.of(1), MessageUid.of(3)),
                new SearchQuery.UidRange(MessageUid.of(7)))), meta, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(SearchQuery.uid(
                new SearchQuery.UidRange(MessageUid.of(8), MessageUid.of(9)))), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(SearchQuery.uid()), meta, rawLoader),
                "пустой UID SET James трактует как ALL");

        assertTrue(CorvexSearch.matches(SearchQuery.of(SearchQuery.sizeEquals(1500)), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(SearchQuery.sizeGreaterThan(1499)), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(SearchQuery.sizeLessThan(1501)), meta, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(SearchQuery.sizeEquals(1501)), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(SearchQuery.modSeqEquals(42)), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(SearchQuery.modSeqGreaterThan(41)), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(SearchQuery.modSeqLessThan(43)), meta, rawLoader));
        assertEquals(0, rawLoads.get(), "метаданные не должны тянуть сырое письмо");
    }

    @Test
    void internalAndHeaderDatesCompareByServerLocalDay() throws Exception {
        var meta = meta();
        Date sameDay = Date.from(INTERNAL_DATE);
        Date previousDay = Date.from(Instant.parse("2026-07-13T12:00:00Z"));
        Date nextDay = Date.from(Instant.parse("2026-07-15T12:00:00Z"));

        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.internalDateOn(sameDay, SearchQuery.DateResolution.Day)), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.internalDateAfter(previousDay, SearchQuery.DateResolution.Day)), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.internalDateBefore(nextDay, SearchQuery.DateResolution.Day)), meta, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.internalDateAfter(sameDay, SearchQuery.DateResolution.Second)), meta, rawLoader),
                "текущая реализация всегда сравнивает календарный день, не секунду");

        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.sentDateOn(sameDay, SearchQuery.DateResolution.Day)), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.headerDateAfter("INTERNALDATE", previousDay, SearchQuery.DateResolution.Day)),
                meta, rawLoader));
        assertThrows(UnsupportedCriteriaException.class, () -> CorvexSearch.matches(SearchQuery.of(
                SearchQuery.headerDateBefore("Received", nextDay, SearchQuery.DateResolution.Day)), meta, rawLoader));

        var nullDate = new ImapStorageClient.MessageMeta(MESSAGE_UUID, 7, 42, null, null, null,
                null, 10, false, false, false, false, false);
        assertFalse(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.internalDateOn(sameDay, SearchQuery.DateResolution.Day)), nullDate, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.subject("anything")), nullDate, rawLoader));
    }

    @Test
    void identityAndHeaderCriteriaUseMetadataBeforeRawFallback() throws Exception {
        var meta = meta();
        assertTrue(CorvexSearch.matches(SearchQuery.of(SearchQuery.mimeMessageID("M1@CORP")), meta, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(SearchQuery.mimeMessageID("other@corp")), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.hasMessageId(CorvexMessageId.of(MESSAGE_UUID))), meta, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.hasMessageId(CorvexMessageId.of(UUID.randomUUID()))), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.threadId(ThreadId.fromBaseMessageId(CorvexMessageId.of(MESSAGE_UUID)))),
                meta, rawLoader));

        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.headerContains("Subject", "ОТЧЁТ")), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.address(SearchQuery.AddressType.From, "Petrov@Corp")), meta, rawLoader));
        assertEquals(0, rawLoads.get());
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.address(SearchQuery.AddressType.To, "ivanov@corp")), meta, rawLoader));
        assertEquals(1, rawLoads.get(), "To нет в метаданных — нужен raw fallback");

        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.headerContains("X-Project", "beta gamma")), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.headerExists("Date")), meta, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.headerExists("X-Empty")), meta, rawLoader),
                "пустое значение не считается существующим содержательным заголовком");
        assertFalse(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.headerContains("X-Missing", "x")), meta, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.headerContains(null, "x")), meta, rawLoader));
    }

    @Test
    void textCriteriaSearchMetadataThenRawAndRejectAttachmentScopes() throws Exception {
        var meta = meta();
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.mailContains("квартальный")), meta, rawLoader));
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.bodyContains("PETROV@CORP")), meta, rawLoader));
        assertEquals(0, rawLoads.get());
        assertTrue(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.bodyContains("needle in raw")), meta, rawLoader));
        assertFalse(CorvexSearch.matches(SearchQuery.of(
                SearchQuery.mailContains("not present anywhere")), meta, rawLoader));
        assertEquals(2, rawLoads.get());

        assertThrows(UnsupportedCriteriaException.class, () -> CorvexSearch.matches(
                SearchQuery.of(SearchQuery.attachmentContains("needle")), meta, rawLoader));
        assertThrows(UnsupportedCriteriaException.class, () -> CorvexSearch.matches(
                SearchQuery.of(SearchQuery.attachmentFileName("report.pdf")), meta, rawLoader));
        assertThrows(UnsupportedCriteriaException.class, () -> CorvexSearch.matches(
                SearchQuery.of(SearchQuery.hasAttachment()), meta, rawLoader));
        assertThrows(UnsupportedCriteriaException.class, () -> CorvexSearch.matches(
                SearchQuery.of(SearchQuery.saveDateAfter(new Date(), SearchQuery.DateResolution.Day)),
                meta, rawLoader));
    }

    @Test
    void unknownHeaderOperatorAndRawLoadFailuresAreNotMasked() throws Exception {
        Constructor<SearchQuery.HeaderCriterion> constructor = SearchQuery.HeaderCriterion.class
                .getDeclaredConstructor(String.class, SearchQuery.HeaderOperator.class);
        constructor.setAccessible(true);
        SearchQuery.Criterion unsupportedOperator = constructor.newInstance("From", new SearchQuery.HeaderOperator() {
        });
        assertThrows(UnsupportedCriteriaException.class,
                () -> CorvexSearch.matches(SearchQuery.of(unsupportedOperator), meta(), rawLoader));

        MailboxException failure = new MailboxException("raw unavailable");
        MailboxException thrown = assertThrows(MailboxException.class, () -> CorvexSearch.matches(
                SearchQuery.of(SearchQuery.headerContains("X-Project", "Alpha")),
                meta(), ignored -> {
                    throw failure;
                }));
        assertEquals(failure, thrown);
    }

    @Test
    void rawHeaderParserHandlesFoldingDuplicatesMalformedLinesAndSeparators() throws Exception {
        Map<String, String> headers = CorvexSearch.parseHeaders(RAW);
        assertEquals("petrov@corp.local", headers.get("From"));
        assertEquals("Alpha, Beta Gamma", headers.get("X-Project"));
        assertEquals("", headers.get("X-Empty"));
        assertFalse(headers.containsKey("Needle in raw body"));

        assertTrue(CorvexSearch.parseHeaders(null).isEmpty());
        assertTrue(CorvexSearch.parseHeaders(new byte[0]).isEmpty());
        Map<String, String> withoutSeparator = CorvexSearch.parseHeaders(
                "Broken line\nX-One: first\nnot-a-header\nX-Two: second".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(Map.of("X-One", "first", "X-Two", "second"), withoutSeparator);
        Map<String, String> foldedAtStart = CorvexSearch.parseHeaders(
                " orphan\n: ignored\nX-Test: value".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(Map.of("X-Test", "value"), foldedAtStart);

        assertEquals(1, CorvexSearch.headerEnd("A\r\n\r\nB\n\n"));
        assertEquals(1, CorvexSearch.headerEnd("A\n\nB\r\n\r\n"));
        assertEquals(3, CorvexSearch.headerEnd("ABC\n\nD"));
        assertEquals(-1, CorvexSearch.headerEnd("ABC"));

        var constructor = CorvexSearch.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }
}
