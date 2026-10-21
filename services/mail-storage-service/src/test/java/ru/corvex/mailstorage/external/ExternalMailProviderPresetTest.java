package ru.corvex.mailstorage.external;

import org.junit.jupiter.api.Test;
import ru.corvex.mailstorage.domain.ExternalMailAccount;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExternalMailProviderPresetTest {

    @Test
    void mailRuAndYandexUseOfficialTlsEndpoints() {
        var mailRu = ExternalMailProviderPreset.forProvider(ExternalMailAccount.Provider.MAIL_RU);
        assertEquals("imap.mail.ru", mailRu.incomingHost(ExternalMailAccount.Protocol.IMAP));
        assertEquals(993, mailRu.incomingPort(ExternalMailAccount.Protocol.IMAP));
        assertEquals("smtp.mail.ru", mailRu.smtpHost());
        assertEquals(465, mailRu.smtpPort());

        var yandex = ExternalMailProviderPreset.forProvider(ExternalMailAccount.Provider.YANDEX);
        assertEquals("imap.yandex.ru", yandex.incomingHost(ExternalMailAccount.Protocol.IMAP));
        assertEquals("smtp.yandex.ru", yandex.smtpHost());
    }

    @Test
    void detectsSpecialFoldersByAttributesAndRussianNames() {
        assertEquals("SENT", JakartaExternalMailClient.specialUse("INBOX/Отправленные", "/", Set.of()));
        assertEquals("SPAM", JakartaExternalMailClient.specialUse("Junk", "/", Set.of("\\junk")));
        assertNull(JakartaExternalMailClient.specialUse("Projects", "/", Set.of()));
    }
}
