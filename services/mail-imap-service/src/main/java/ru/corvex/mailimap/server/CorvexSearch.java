package ru.corvex.mailimap.server;

import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.exception.UnsupportedCriteriaException;
import org.apache.james.mailbox.model.SearchQuery;
import ru.corvex.mailimap.client.ImapStorageClient;
import ru.corvex.mailimap.model.ImapFlags;

import javax.mail.Flags;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Оценка James SearchQuery по метаданным хранилища. Поддерживает критерии, которые реально
 * шлют почтовые клиенты: флаги, UID, размер, даты INTERNALDATE, Тема/FROM/HEADER/MESSAGE-ID,
 * BODY/TEXT (лениво по сырому .eml), MODSEQ (CONDSTORE). Неподдержанное честно даёт
 * UnsupportedCriteriaException -> IMAP NO, а не тихий неверный результат.
 */
final class CorvexSearch {

    @FunctionalInterface
    interface RawLoader {
        byte[] load(ImapStorageClient.MessageMeta meta) throws MailboxException;
    }

    private CorvexSearch() {
    }

    static boolean matches(SearchQuery query, ImapStorageClient.MessageMeta meta, RawLoader rawLoader)
            throws MailboxException {
        for (SearchQuery.Criterion criterion : query.getCriteria()) {
            if (!matches(criterion, meta, rawLoader)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(SearchQuery.Criterion criterion, ImapStorageClient.MessageMeta meta,
                                   RawLoader rawLoader) throws MailboxException {
        if (criterion instanceof SearchQuery.AllCriterion) {
            return true;
        }
        if (criterion instanceof SearchQuery.ConjunctionCriterion conjunction) {
            return switch (conjunction.getType()) {
                case AND -> all(conjunction, meta, rawLoader);
                case OR -> any(conjunction, meta, rawLoader);
                case NOR -> !any(conjunction, meta, rawLoader);
            };
        }
        if (criterion instanceof SearchQuery.FlagCriterion flag) {
            return flagValue(meta, flag.getFlag()) == flag.getOperator().isSet();
        }
        if (criterion instanceof SearchQuery.UidCriterion uid) {
            MessageUid value = MessageUid.of(meta.uid());
            for (SearchQuery.UidRange range : uid.getOperator().getRange()) {
                if (range.isIn(value)) {
                    return true;
                }
            }
            return uid.getOperator().isAll();
        }
        if (criterion instanceof SearchQuery.InternalDateCriterion date) {
            return dateMatches(meta.internalDate(), date.getOperator());
        }
        if (criterion instanceof SearchQuery.SizeCriterion size) {
            return numericMatches(meta.sizeBytes(), size.getOperator());
        }
        if (criterion instanceof SearchQuery.ModSeqCriterion modSeq) {
            return numericMatches(meta.modseq(), modSeq.getOperator());
        }
        if (criterion instanceof SearchQuery.SubjectCriterion subject) {
            return contains(meta.subject(), subject.getSubject());
        }
        if (criterion instanceof SearchQuery.MimeMessageIDCriterion messageId) {
            return contains(meta.messageIdHeader(), messageId.getMessageID());
        }
        if (criterion instanceof SearchQuery.MessageIdCriterion messageId) {
            return meta.id().toString().equals(messageId.getMessageId().serialize());
        }
        if (criterion instanceof SearchQuery.ThreadIdCriterion threadId) {
            return meta.id().toString().equals(threadId.getThreadId().getBaseMessageId().serialize());
        }
        if (criterion instanceof SearchQuery.HeaderCriterion header) {
            return headerMatches(header, meta, rawLoader);
        }
        if (criterion instanceof SearchQuery.TextCriterion text) {
            return textMatches(text, meta, rawLoader);
        }
        if (criterion instanceof SearchQuery.CustomFlagCriterion) {
            // Пользовательские флаги/ключевые слова хранилище не персистит: UNKEYWORD x == true, KEYWORD x == false.
            SearchQuery.CustomFlagCriterion custom = (SearchQuery.CustomFlagCriterion) criterion;
            return !custom.getOperator().isSet();
        }
        throw new UnsupportedCriteriaException();
    }

    private static boolean all(SearchQuery.ConjunctionCriterion conjunction, ImapStorageClient.MessageMeta meta,
                               RawLoader rawLoader) throws MailboxException {
        for (SearchQuery.Criterion criterion : conjunction.getCriteria()) {
            if (!matches(criterion, meta, rawLoader)) {
                return false;
            }
        }
        return true;
    }

    private static boolean any(SearchQuery.ConjunctionCriterion conjunction, ImapStorageClient.MessageMeta meta,
                               RawLoader rawLoader) throws MailboxException {
        for (SearchQuery.Criterion criterion : conjunction.getCriteria()) {
            if (matches(criterion, meta, rawLoader)) {
                return true;
            }
        }
        return false;
    }

    private static boolean flagValue(ImapStorageClient.MessageMeta meta, Flags.Flag flag) {
        Set<String> flags = ImapFlags.fromMeta(meta);
        if (flag == Flags.Flag.SEEN) return flags.contains(ImapFlags.SEEN);
        if (flag == Flags.Flag.ANSWERED) return flags.contains(ImapFlags.ANSWERED);
        if (flag == Flags.Flag.FLAGGED) return flags.contains(ImapFlags.FLAGGED);
        if (flag == Flags.Flag.DRAFT) return flags.contains(ImapFlags.DRAFT);
        if (flag == Flags.Flag.DELETED) return flags.contains(ImapFlags.DELETED);
        if (flag == Flags.Flag.RECENT) return !meta.seen(); // приближение: \Recent в хранилище не хранится
        return false;
    }

    private static boolean numericMatches(long actual, SearchQuery.NumericOperator operator) {
        return switch (operator.getType()) {
            case LESS_THAN -> actual < operator.getValue();
            case GREATER_THAN -> actual > operator.getValue();
            case EQUALS -> actual == operator.getValue();
        };
    }

    private static boolean dateMatches(Instant actual, SearchQuery.DateOperator operator) {
        Date expected = operator.getDate();
        if (actual == null || expected == null) {
            return false;
        }
        // IMAP-даты сравниваются с точностью до дня в локальной зоне сервера.
        var zone = ZoneId.systemDefault();
        var actualDay = actual.atZone(zone).toLocalDate();
        var expectedDay = expected.toInstant().atZone(zone).toLocalDate();
        return switch (operator.getType()) {
            case BEFORE -> actualDay.isBefore(expectedDay);
            case AFTER -> actualDay.isAfter(expectedDay);
            case ON -> actualDay.isEqual(expectedDay);
        };
    }

    private static boolean headerMatches(SearchQuery.HeaderCriterion header, ImapStorageClient.MessageMeta meta,
                                         RawLoader rawLoader) throws MailboxException {
        String name = header.getHeaderName() == null ? "" : header.getHeaderName();
        SearchQuery.HeaderOperator operator = header.getOperator();
        if (operator instanceof SearchQuery.DateOperator date) {
            if (name.equalsIgnoreCase("Date") || name.equalsIgnoreCase("INTERNALDATE")) {
                return dateMatches(meta.internalDate(), date);
            }
            throw new UnsupportedCriteriaException();
        }
        String value = headerValue(name, meta, rawLoader);
        if (operator instanceof SearchQuery.ExistsOperator) {
            return value != null && !value.isBlank();
        }
        if (operator instanceof SearchQuery.ContainsOperator contains) {
            return contains(value, contains.getValue());
        }
        if (operator instanceof SearchQuery.AddressOperator address) {
            return contains(value, address.getAddress());
        }
        throw new UnsupportedCriteriaException();
    }

    private static String headerValue(String name, ImapStorageClient.MessageMeta meta, RawLoader rawLoader)
            throws MailboxException {
        if (name.equalsIgnoreCase("From")) return meta.sender();
        if (name.equalsIgnoreCase("Subject")) return meta.subject();
        if (name.equalsIgnoreCase("Message-ID")) return meta.messageIdHeader();
        if (name.equalsIgnoreCase("Date")) return meta.internalDate() == null ? null : meta.internalDate().toString();
        Map<String, String> headers = parseHeaders(rawLoader.load(meta));
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean textMatches(SearchQuery.TextCriterion text, ImapStorageClient.MessageMeta meta,
                                       RawLoader rawLoader) throws MailboxException {
        String needle = text.getOperator().getValue();
        if (text.getType() == SearchQuery.Scope.FULL || text.getType() == SearchQuery.Scope.BODY) {
            // Метаданных часто хватает для TEXT (клиенты ищут по From/Subject этим же критерием).
            if (contains(meta.subject(), needle) || contains(meta.sender(), needle)) {
                return true;
            }
            byte[] raw = rawLoader.load(meta);
            String haystack = new String(raw, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            return haystack.contains(needle.toLowerCase(Locale.ROOT));
        }
        throw new UnsupportedCriteriaException();
    }

    static Map<String, String> parseHeaders(byte[] raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (raw == null || raw.length == 0) {
            return headers;
        }
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int split = headerEnd(text);
        String head = split >= 0 ? text.substring(0, split) : text;
        String currentName = null;
        StringBuilder currentValue = new StringBuilder();
        for (String line : head.split("\\r\\n|\\n", -1)) {
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t') && currentName != null) {
                currentValue.append(' ').append(line.trim());
                continue;
            }
            if (currentName != null) {
                headers.merge(currentName, currentValue.toString().trim(), (a, b) -> a + ", " + b);
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                currentName = null;
                currentValue = new StringBuilder();
                continue;
            }
            currentName = line.substring(0, colon).trim();
            currentValue = new StringBuilder(line.substring(colon + 1).trim());
        }
        if (currentName != null) {
            headers.merge(currentName, currentValue.toString().trim(), (a, b) -> a + ", " + b);
        }
        return headers;
    }

    static int headerEnd(String text) {
        int crlf = text.indexOf("\r\n\r\n");
        int lf = text.indexOf("\n\n");
        if (crlf < 0) return lf;
        if (lf < 0) return crlf;
        return Math.min(crlf, lf);
    }

    private static boolean contains(String haystack, String needle) {
        if (haystack == null || needle == null) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
