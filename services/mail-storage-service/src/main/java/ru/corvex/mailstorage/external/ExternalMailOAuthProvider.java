package ru.corvex.mailstorage.external;

import ru.corvex.mailstorage.domain.ExternalMailAccount;

/**
 * Провайдеры, у которых почта открывается по OAuth2.
 *
 * <p>Без OAuth Microsoft 365 не отдаёт почту вообще: basic auth отключён с 2022 года.
 * Gmail работает и с паролем приложения, но в рабочих доменах Google такая политика часто
 * запрещена администратором, поэтому OAuth нужен и там.
 *
 * <p>Области доступа минимальны: полный доступ к почте через IMAP/SMTP, чтение адреса
 * из id_token. Контакты, календарь и диск не запрашиваются.
 */
public enum ExternalMailOAuthProvider {

    GOOGLE("Google",
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "openid email profile https://mail.google.com/",
            "Подойдёт аккаунт Google или Google Workspace с включённым IMAP."),

    MICROSOFT("Microsoft 365",
            "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize",
            "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token",
            "openid email offline_access https://outlook.office.com/IMAP.AccessAsUser.All "
                    + "https://outlook.office.com/SMTP.Send",
            "Подойдёт рабочая или личная учётная запись Microsoft; пароль приложения не нужен.");

    private final String title;
    private final String authorizeEndpoint;
    private final String tokenEndpoint;
    private final String scope;
    private final String hint;

    ExternalMailOAuthProvider(String title, String authorizeEndpoint, String tokenEndpoint,
                              String scope, String hint) {
        this.title = title;
        this.authorizeEndpoint = authorizeEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.scope = scope;
        this.hint = hint;
    }

    public String title() {
        return title;
    }

    public String authorizeEndpoint() {
        return authorizeEndpoint;
    }

    public String tokenEndpoint() {
        return tokenEndpoint;
    }

    public String scope() {
        return scope;
    }

    public String hint() {
        return hint;
    }

    /** Соответствие провайдеру внешнего ящика; для остальных источников OAuth не предлагается. */
    public static ExternalMailOAuthProvider forAccountProvider(ExternalMailAccount.Provider provider) {
        if (provider == null) {
            return null;
        }
        return switch (provider) {
            case GMAIL -> GOOGLE;
            case OUTLOOK -> MICROSOFT;
            default -> null;
        };
    }

    /** Обратное соответствие нужно при создании подключения из внешнего ящика. */
    public ExternalMailAccount.Provider accountProvider() {
        return this == GOOGLE ? ExternalMailAccount.Provider.GMAIL : ExternalMailAccount.Provider.OUTLOOK;
    }

    public static ExternalMailOAuthProvider parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
