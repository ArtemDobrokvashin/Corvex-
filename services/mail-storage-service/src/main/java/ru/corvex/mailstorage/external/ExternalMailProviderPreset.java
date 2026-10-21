package ru.corvex.mailstorage.external;

import ru.corvex.mailstorage.domain.ExternalMailAccount;

/** Официальные endpoint-пресеты. Для preset-провайдеров пользователь не может подменить хост. */
public enum ExternalMailProviderPreset {
    MAIL_RU("imap.mail.ru", 993, "pop.mail.ru", 995, "smtp.mail.ru", 465,
            ExternalMailAccount.Security.SSL_TLS, ExternalMailAccount.Security.SSL_TLS,
            "Создайте пароль внешнего приложения Mail.ru; обычный пароль ящика не подходит."),
    YANDEX("imap.yandex.ru", 993, "pop.yandex.ru", 995, "smtp.yandex.ru", 465,
            ExternalMailAccount.Security.SSL_TLS, ExternalMailAccount.Security.SSL_TLS,
            "Включите доступ по IMAP и создайте пароль приложения Яндекс ID для «Почты»."),
    GMAIL("imap.gmail.com", 993, "pop.gmail.com", 995, "smtp.gmail.com", 465,
            ExternalMailAccount.Security.SSL_TLS, ExternalMailAccount.Security.SSL_TLS,
            "Нужен App Password Google при включённой двухэтапной проверке либо политика Workspace."),
    OUTLOOK("outlook.office365.com", 993, "outlook.office365.com", 995, "smtp-mail.outlook.com", 587,
            ExternalMailAccount.Security.SSL_TLS, ExternalMailAccount.Security.STARTTLS,
            "Microsoft часто требует Modern Auth/OAuth2; app-password доступен не для всех тенантов.");

    private final String imapHost;
    private final int imapPort;
    private final String pop3Host;
    private final int pop3Port;
    private final String smtpHost;
    private final int smtpPort;
    private final ExternalMailAccount.Security incomingSecurity;
    private final ExternalMailAccount.Security smtpSecurity;
    private final String hint;

    ExternalMailProviderPreset(String imapHost, int imapPort, String pop3Host, int pop3Port,
                               String smtpHost, int smtpPort,
                               ExternalMailAccount.Security incomingSecurity,
                               ExternalMailAccount.Security smtpSecurity,
                               String hint) {
        this.imapHost = imapHost;
        this.imapPort = imapPort;
        this.pop3Host = pop3Host;
        this.pop3Port = pop3Port;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.incomingSecurity = incomingSecurity;
        this.smtpSecurity = smtpSecurity;
        this.hint = hint;
    }

    public static ExternalMailProviderPreset forProvider(ExternalMailAccount.Provider provider) {
        return switch (provider) {
            case MAIL_RU -> MAIL_RU;
            case YANDEX -> YANDEX;
            case GMAIL -> GMAIL;
            case OUTLOOK -> OUTLOOK;
            case CUSTOM -> null;
        };
    }

    public String incomingHost(ExternalMailAccount.Protocol protocol) {
        return protocol == ExternalMailAccount.Protocol.POP3 ? pop3Host : imapHost;
    }

    public int incomingPort(ExternalMailAccount.Protocol protocol) {
        return protocol == ExternalMailAccount.Protocol.POP3 ? pop3Port : imapPort;
    }

    public String smtpHost() { return smtpHost; }

    public int smtpPort() { return smtpPort; }

    public ExternalMailAccount.Security incomingSecurity() { return incomingSecurity; }

    public ExternalMailAccount.Security smtpSecurity() { return smtpSecurity; }

    public String hint() { return hint; }
}
