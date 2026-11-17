package ru.corvex.mailstorage.contacts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.ContactDtos;
import ru.corvex.mailstorage.domain.Contact;
import ru.corvex.mailstorage.repository.ContactRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContactServiceTest {

    private ContactRepository repository;
    private ContactService service;
    private UUID owner;

    @BeforeEach
    void setUp() {
        repository = mock(ContactRepository.class);
        service = new ContactService(repository);
        owner = UUID.randomUUID();
        when(repository.saveAndFlush(any(Contact.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.save(any(Contact.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.countByOwnerId(any())).thenReturn(0L);
        when(repository.findByOwnerIdAndEmailIgnoreCase(any(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void createRejectsDuplicateAddressIgnoringCase() {
        // Заглушка повторяет поведение БД: derived-запрос ...IgnoreCase сравнивает
        // адреса без учёта регистра, поэтому точное совпадение строки здесь неверно.
        when(repository.findByOwnerIdAndEmailIgnoreCase(any(), anyString())).thenAnswer(invocation ->
                "ivan@example.ru".equalsIgnoreCase(invocation.getArgument(1))
                        ? Optional.of(contact("Иван", "Ivan@example.ru"))
                        : Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.create(owner,
                new ContactDtos.ContactRequest("Иван", "IVAN@example.ru", null, null, null, null, null)));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void createRejectsMalformedAddress() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.create(owner,
                new ContactDtos.ContactRequest("Иван", "не адрес", null, null, null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void createFallsBackToLocalPartWhenNameIsMissing() {
        ContactDtos.ContactView view = service.create(owner,
                new ContactDtos.ContactRequest(null, "petr@example.ru", "+7 999", "Ромашка", null, null, null));

        assertEquals("petr", view.displayName());
        assertEquals("+7 999", view.phone());
        assertEquals("MANUAL", view.source());
    }

    @Test
    void importCreatesNewContactsAndDoesNotOverwriteFilledFields() {
        Contact existing = contact("Иван", "ivan@example.ru");
        existing.setPhone("+7 111");
        when(repository.findAllByOwnerIdOrderByDisplayNameAsc(owner)).thenReturn(List.of(existing));

        String csv = "Name,E-mail,Phone\n"
                + "Иван,IVAN@example.ru,+7 999\n"
                + "Пётр,petr@example.ru,\n"
                + "Без адреса,,\n";

        ContactDtos.ImportResult result = service.importContacts(owner, "contacts.csv",
                csv.getBytes(StandardCharsets.UTF_8), ContactImportParser.Format.AUTO);

        assertEquals(1, result.created());
        assertEquals(0, result.updated());
        assertEquals(1, result.skipped());
        assertEquals(1, result.issues().size());
        assertEquals("+7 111", existing.getPhone());
    }

    @Test
    void importFillsEmptyFieldsOfExistingContact() {
        Contact existing = contact("Иван", "ivan@example.ru");
        when(repository.findAllByOwnerIdOrderByDisplayNameAsc(owner)).thenReturn(List.of(existing));

        String vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:Иван Петров\nEMAIL:ivan@example.ru\n"
                + "TEL:+7 999\nORG:Ромашка\nEND:VCARD\n";

        ContactDtos.ImportResult result = service.importContacts(owner, "contacts.vcf",
                vcf.getBytes(StandardCharsets.UTF_8), ContactImportParser.Format.AUTO);

        assertEquals(1, result.updated());
        assertEquals("+7 999", existing.getPhone());
        assertEquals("Ромашка", existing.getOrganization());
        // Имя остаётся прежним: импорт не переименовывает уже заведённый контакт.
        assertEquals("Иван", existing.getDisplayName());
    }

    @Test
    void importReportsInvalidAddressInsteadOfFailingWholeFile() {
        String csv = "Name,E-mail\nИван,ivan@example.ru\nСломанный,not-an-address\n";
        when(repository.findAllByOwnerIdOrderByDisplayNameAsc(owner)).thenReturn(List.of());

        ContactDtos.ImportResult result = service.importContacts(owner, "contacts.csv",
                csv.getBytes(StandardCharsets.UTF_8), ContactImportParser.Format.CSV);

        assertEquals(1, result.created());
        assertEquals(1, result.skipped());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.reason().contains("некорректный")));
    }

    @Test
    void exportEscapesVcardSpecialCharacters() {
        Contact contact = contact("Иван; Петров", "ivan@example.ru");
        contact.setNote("первая строка\nвторая");
        when(repository.findAllByOwnerIdOrderByDisplayNameAsc(owner)).thenReturn(List.of(contact));

        String vcf = new String(service.export(owner, "vcf").content(), StandardCharsets.UTF_8);

        assertTrue(vcf.contains("FN:Иван\\; Петров"));
        assertTrue(vcf.contains("NOTE:первая строка\\nвторая"));
        assertTrue(vcf.startsWith("BEGIN:VCARD"));
    }

    @Test
    void exportCsvStartsWithBomForExcel() {
        when(repository.findAllByOwnerIdOrderByDisplayNameAsc(owner)).thenReturn(List.of());
        byte[] content = service.export(owner, "csv").content();

        assertEquals((byte) 0xEF, content[0]);
        assertEquals((byte) 0xBB, content[1]);
        assertEquals((byte) 0xBF, content[2]);
    }

    @Test
    void deleteRejectsContactOwnedBySomeoneElse() {
        when(repository.findByIdAndOwnerId(any(), any())).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.delete(owner, UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    private static Contact contact(String name, String email) {
        Contact contact = new Contact();
        contact.setId(UUID.randomUUID());
        contact.setOwnerId(UUID.randomUUID());
        contact.setDisplayName(name);
        contact.setEmail(email);
        contact.setSource("MANUAL");
        return contact;
    }
}
