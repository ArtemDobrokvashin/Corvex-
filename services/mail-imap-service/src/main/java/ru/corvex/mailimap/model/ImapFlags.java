package ru.corvex.mailimap.model;

import ru.corvex.mailimap.client.ImapStorageClient;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Преобразование между флагами IMAP (RFC 9051 §2.3.2) и колонками message_metadata.
 * Пользовательских keyword-флагов в хранилище пока нет (PERMANENTFLAGS без \*) —
 * неизвестные ключевые слова движок должен молча игнорировать, поэтому здесь они
 * просто не попадают в FlagsUpdate.
 */
public final class ImapFlags {

    public static final String SEEN = "\\Seen";
    public static final String ANSWERED = "\\Answered";
    public static final String FLAGGED = "\\Flagged";
    public static final String DRAFT = "\\Draft";
    public static final String DELETED = "\\Deleted";

    /** Флаги, которые хранилище умеет персистентно держать (для PERMANENTFLAGS в SELECT). */
    public static final Set<String> SUPPORTED = Set.of(SEEN, ANSWERED, FLAGGED, DRAFT, DELETED);

    private ImapFlags() {
    }

    public static Set<String> fromMeta(ImapStorageClient.MessageMeta m) {
        Set<String> out = new LinkedHashSet<>();
        if (m.seen()) out.add(SEEN);
        if (m.answered()) out.add(ANSWERED);
        if (m.flagged()) out.add(FLAGGED);
        if (m.draft()) out.add(DRAFT);
        if (m.deleted()) out.add(DELETED);
        return out;
    }

    /** STORE FLAGS (полная замена набора). */
    public static ImapStorageClient.FlagsUpdate replaceWith(Set<String> flags) {
        Set<String> normalized = normalize(flags);
        return new ImapStorageClient.FlagsUpdate(
                normalized.contains(SEEN), normalized.contains(ANSWERED), normalized.contains(FLAGGED),
                normalized.contains(DRAFT), normalized.contains(DELETED));
    }

    /** STORE +FLAGS (добавить к текущим; остальные поля null = не трогать). */
    public static ImapStorageClient.FlagsUpdate add(Set<String> flags) {
        Set<String> normalized = normalize(flags);
        return new ImapStorageClient.FlagsUpdate(
                normalized.contains(SEEN) ? Boolean.TRUE : null,
                normalized.contains(ANSWERED) ? Boolean.TRUE : null,
                normalized.contains(FLAGGED) ? Boolean.TRUE : null,
                normalized.contains(DRAFT) ? Boolean.TRUE : null,
                normalized.contains(DELETED) ? Boolean.TRUE : null);
    }

    /** STORE -FLAGS (снять перечисленные). */
    public static ImapStorageClient.FlagsUpdate remove(Set<String> flags) {
        Set<String> normalized = normalize(flags);
        return new ImapStorageClient.FlagsUpdate(
                normalized.contains(SEEN) ? Boolean.FALSE : null,
                normalized.contains(ANSWERED) ? Boolean.FALSE : null,
                normalized.contains(FLAGGED) ? Boolean.FALSE : null,
                normalized.contains(DRAFT) ? Boolean.FALSE : null,
                normalized.contains(DELETED) ? Boolean.FALSE : null);
    }

    /** Флаги регистронезависимы; \Recent не персистится (сессионный флаг движка). */
    static Set<String> normalize(Set<String> flags) {
        Set<String> out = new LinkedHashSet<>();
        if (flags == null) {
            return out;
        }
        for (String f : flags) {
            if (f == null) {
                continue;
            }
            switch (f.trim().toLowerCase(Locale.ROOT)) {
                case "\\seen" -> out.add(SEEN);
                case "\\answered" -> out.add(ANSWERED);
                case "\\flagged" -> out.add(FLAGGED);
                case "\\draft" -> out.add(DRAFT);
                case "\\deleted" -> out.add(DELETED);
                default -> {
                    // keyword-флаги и \Recent хранилище не держит — игнорируем по RFC
                }
            }
        }
        return out;
    }
}
