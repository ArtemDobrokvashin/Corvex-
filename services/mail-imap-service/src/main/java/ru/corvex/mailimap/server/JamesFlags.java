package ru.corvex.mailimap.server;

import ru.corvex.mailimap.client.ImapStorageClient;
import ru.corvex.mailimap.model.ImapFlags;

import javax.mail.Flags;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Конвертация между тремя представлениями флагов: javax.mail.Flags (James SPI 3.8.2),
 * строковый набор модели Corvex ("\\seen") и DTO протокольного API хранилища.
 */
final class JamesFlags {

    private JamesFlags() {
    }

    static Flags fromMeta(ImapStorageClient.MessageMeta meta) {
        return fromStrings(ImapFlags.fromMeta(meta));
    }

    static Flags fromStrings(Set<String> flags) {
        Flags out = new Flags();
        if (flags == null) {
            return out;
        }
        for (String flag : flags) {
            switch (flag.toLowerCase(java.util.Locale.ROOT)) {
                case "\\seen" -> out.add(Flags.Flag.SEEN);
                case "\\answered" -> out.add(Flags.Flag.ANSWERED);
                case "\\flagged" -> out.add(Flags.Flag.FLAGGED);
                case "\\draft" -> out.add(Flags.Flag.DRAFT);
                case "\\deleted" -> out.add(Flags.Flag.DELETED);
                case "\\recent" -> out.add(Flags.Flag.RECENT);
                default -> {
                    // Пользовательские ключевые слова хранилище пока не персистит: в James Flags не кладём,
                    // иначе клиент увидит флаг, который пропадёт после перелогина.
                }
            }
        }
        return out;
    }

    static Set<String> toStrings(Flags flags) {
        Set<String> out = new LinkedHashSet<>();
        if (flags == null) {
            return out;
        }
        if (flags.contains(Flags.Flag.SEEN)) out.add(ImapFlags.SEEN);
        if (flags.contains(Flags.Flag.ANSWERED)) out.add(ImapFlags.ANSWERED);
        if (flags.contains(Flags.Flag.FLAGGED)) out.add(ImapFlags.FLAGGED);
        if (flags.contains(Flags.Flag.DRAFT)) out.add(ImapFlags.DRAFT);
        if (flags.contains(Flags.Flag.DELETED)) out.add(ImapFlags.DELETED);
        if (flags.contains(Flags.Flag.RECENT)) out.add("\\Recent");
        return out;
    }

    /** Заголовок X-Corvex-Flags для APPEND в протокольном API хранилища. */
    static String toHeader(Flags flags) {
        Set<String> values = toStrings(flags);
        values.remove("\\Recent"); // \Recent — сессионный, в хранилище не пишется
        return String.join(" ", values);
    }

    static ImapStorageClient.FlagsUpdate toUpdate(Set<String> flags) {
        return ImapFlags.replaceWith(flags);
    }

    static Flags applicableFlags() {
        Flags flags = new Flags();
        flags.add(Flags.Flag.SEEN);
        flags.add(Flags.Flag.ANSWERED);
        flags.add(Flags.Flag.FLAGGED);
        flags.add(Flags.Flag.DRAFT);
        flags.add(Flags.Flag.DELETED);
        flags.add(Flags.Flag.RECENT);
        return flags;
    }

    static Flags permanentFlags() {
        Flags flags = applicableFlags();
        flags.remove(Flags.Flag.RECENT);
        return flags;
    }

}
