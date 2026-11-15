package ru.corvex.mailstorage.maildomain;

import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Строгий разбор доменного имени для админского API.
 *
 * <p>Разрешены только ASCII-метки после IDN-преобразования, нижний регистр,
 * длина домена до 253 символов, метки 1–63 символа, буква или цифра на концах.
 * Это же значение уходит в путь James WebAdmin `/domains/{domain}`, поэтому
 * дополнительные символы (в том числе `/`, `?`, `%`, пробел, wildcard) запрещены
 * до любых сетевых вызовов.
 */
public final class MailDomainName {

    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final int MAX_DOMAIN_LENGTH = 253;

    private MailDomainName() {
    }

    /**
     * @throws IllegalArgumentException если значение не является доменом, пригодным
     *                                  для локальной почты и пути WebAdmin
     */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Домен не задан");
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 320) {
            throw new IllegalArgumentException("Некорректная длина домена");
        }
        if (value.indexOf('@') >= 0 || value.indexOf('/') >= 0 || value.indexOf('?') >= 0
                || value.indexOf('%') >= 0 || value.indexOf('#') >= 0 || value.indexOf('\\') >= 0
                || value.indexOf('*') >= 0 || value.indexOf('_') >= 0) {
            throw new IllegalArgumentException("Домен содержит недопустимые символы");
        }
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        String ascii;
        try {
            ascii = IDN.toASCII(value, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Домен не преобразуется в ASCII (IDN)");
        }
        if (ascii.isEmpty() || ascii.length() > MAX_DOMAIN_LENGTH) {
            throw new IllegalArgumentException("Некорректная длина домена");
        }
        if (IPV4.matcher(ascii).matches()) {
            throw new IllegalArgumentException("IP-адрес не является почтовым доменом");
        }
        String[] labels = ascii.split("\\.", -1);
        if (labels.length < 2) {
            throw new IllegalArgumentException("Домен должен содержать хотя бы одну точку");
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("Некорректная метка домена");
            }
        }
        String topLevel = labels[labels.length - 1];
        if (topLevel.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Верхний уровень домена не может быть числовым");
        }
        return ascii;
    }
}
