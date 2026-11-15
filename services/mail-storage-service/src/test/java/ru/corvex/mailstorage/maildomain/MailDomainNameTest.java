package ru.corvex.mailstorage.maildomain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MailDomainNameTest {

    @Test void normalizesCaseAndTrailingDot() {
        assertEquals("company.local", MailDomainName.normalize(" Company.Local. "));
        assertEquals("company.local", MailDomainName.normalize("COMPANY.LOCAL"));
    }

    @Test void convertsIdnToAscii() {
        assertEquals("xn--e1afmkfd.xn--p1ai", MailDomainName.normalize("пример.рф"));
    }

    @Test void rejectsInjectionIntoWebAdminPath() {
        for (String bad : new String[] {"company.local/../users/x", "company.local?force=true",
                "company.local%2f", "company local", "company_local.ru", "*.company.local", "a@b.c"}) {
            assertThrows(IllegalArgumentException.class, () -> MailDomainName.normalize(bad), bad);
        }
    }

    @Test void rejectsStructurallyInvalidDomains() {
        for (String bad : new String[] {null, "", "   ", "localhost", "192.168.0.1", "-bad.com",
                "bad-.com", "bad..com", "company.123", "a".repeat(64) + ".com"}) {
            assertThrows(IllegalArgumentException.class, () -> MailDomainName.normalize(bad), String.valueOf(bad));
        }
    }

    @Test void allowsCommonLocalMailDomain() {
        assertEquals("company.local", MailDomainName.normalize("company.local"));
        assertEquals("mail.company.co.uk", MailDomainName.normalize("mail.company.co.uk"));
    }
}
