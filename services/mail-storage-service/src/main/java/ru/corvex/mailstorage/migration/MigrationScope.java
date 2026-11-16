package ru.corvex.mailstorage.migration;

import ru.corvex.mailstorage.domain.ExternalMailAccount;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Что именно переносить и сколько это примерно займёт.
 *
 * <p>Класс намеренно не зависит от IMAP-клиента и базы: правила «какие папки пропустить»
 * и «как оценить объём по выборке» — единственное место, где легко ошибиться молча,
 * поэтому они покрыты отдельными тестами.
 */
public final class MigrationScope {

    /**
     * Служебные папки Gmail: письмо лежит в них и одновременно в Inbox или в своей метке.
     * Переносить их по умолчанию нельзя — архив удвоится, а квота израсходуется впустую.
     */
    private static final List<String> GMAIL_DUPLICATES = List.of("all mail", "important", "starred");

    private final ExternalMailAccount.Provider provider;
    private final boolean includeAllMail;
    private final Set<String> skippedFolders;

    public MigrationScope(ExternalMailAccount.Provider provider, boolean includeAllMail,
                          Collection<String> skippedFolders) {
        this.provider = provider == null ? ExternalMailAccount.Provider.CUSTOM : provider;
        this.includeAllMail = includeAllMail;
        Set<String> normalized = new LinkedHashSet<>();
        if (skippedFolders != null) {
            for (String folder : skippedFolders) {
                if (folder != null && !folder.isBlank()) normalized.add(normalize(folder));
            }
        }
        this.skippedFolders = Set.copyOf(normalized);
    }

    public static MigrationScope of(ExternalMailAccount.Provider provider, boolean includeAllMail, String csv) {
        return new MigrationScope(provider, includeAllMail, splitCsv(csv));
    }

    /** Строка для хранения в БД: по одной папке в строке, без пустых значений. */
    public static String joinCsv(Collection<String> folders) {
        if (folders == null || folders.isEmpty()) return null;
        return folders.stream()
                .filter(f -> f != null && !f.isBlank())
                .map(MigrationScope::normalize)
                .distinct()
                .sorted()
                .reduce((a, b) -> a + "\n" + b)
                .orElse(null);
    }

    public static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return value.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /**
     * Пропускать ли папку.
     *
     * @param name       полное имя папки в источнике
     * @param specialUse атрибут RFC 6154 в верхнем регистре или null
     */
    public boolean skip(String name, String specialUse) {
        if (name == null || name.isBlank()) return true;
        String normalizedName = normalize(name);
        if (skippedFolders.contains(normalizedName)) return true;
        if (includeAllMail) return false;
        if (provider == ExternalMailAccount.Provider.GMAIL) {
            String lastSegment = lastSegment(normalizedName);
            return GMAIL_DUPLICATES.contains(lastSegment);
        }
        return false;
    }

    /** Дубли Gmail определяются по имени, а не special-use: Important и Starred его не сообщают. */
    private static String lastSegment(String normalizedName) {
        int slash = Math.max(normalizedName.lastIndexOf('/'), normalizedName.lastIndexOf('\\'));
        return slash >= 0 ? normalizedName.substring(slash + 1).trim() : normalizedName;
    }

    /**
     * Оценка объёма папки по выборке. Точное суммирование, если выборка покрывает всю папку;
     * иначе средний размер письма умножается на количество. Оценка не обязана быть точной,
     * её задача — до старта показать, помещается ли архив в квоту.
     */
    public static long estimateBytes(long messageCount, List<Long> sampleSizes) {
        if (messageCount <= 0 || sampleSizes == null || sampleSizes.isEmpty()) return 0;
        long sum = 0;
        for (Long size : sampleSizes) {
            if (size != null && size > 0) sum += size;
        }
        if (sum <= 0) return 0;
        long measured = sampleSizes.stream().filter(s -> s != null && s > 0).count();
        if (measured == 0) return 0;
        if (messageCount <= measured) return sum;
        return Math.round((double) sum / measured * messageCount);
    }

    /** Размер выборки для preview: достаточно, чтобы средняя не скакала от одного вложения. */
    public static int sampleSize(int messageCount, int maxSample) {
        if (messageCount <= 0) return 0;
        return Math.min(messageCount, Math.max(1, maxSample));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
