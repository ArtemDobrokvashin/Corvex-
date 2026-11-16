package ru.corvex.mailstorage.contacts;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.ContactDtos;
import ru.corvex.mailstorage.domain.Contact;
import ru.corvex.mailstorage.repository.ContactRepository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Личная адресная книга. Все операции ограничены владельцем из JWT: админского доступа
 * к чужим контактам нет намеренно — это личные данные, а не корпоративный справочник.
 *
 * <p>Импорт не перезаписывает заполненные поля: если контакт уже есть, пустые поля
 * дополняются, а различия остаются за пользователем. Так повторный импорт из другого
 * источника не затирает ручные правки.
 */
@Service
public class ContactService {

    /** Потолок на пользователя: 10 000 контактов — предел, при котором поиск в UI остаётся быстрым. */
    static final int MAX_CONTACTS = 10_000;
    static final int MAX_IMPORT_BYTES = 5 * 1024 * 1024;
    static final int MAX_IMPORT_ISSUES = 200;
    private static final int DEFAULT_LIMIT = 500;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final ContactRepository contacts;

    public ContactService(ContactRepository contacts) {
        this.contacts = contacts;
    }

    @Transactional(readOnly = true)
    public List<ContactDtos.ContactView> list(UUID ownerId, String query, Integer limit) {
        int safeLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, 1000));
        PageRequest page = PageRequest.of(0, safeLimit);
        List<Contact> found = query == null || query.isBlank()
                ? contacts.findByOwnerIdOrderByDisplayNameAsc(ownerId, page)
                : contacts.search(ownerId, "%" + query.trim().toLowerCase(Locale.ROOT) + "%", page);
        return found.stream().map(ContactService::toView).toList();
    }

    @Transactional
    public ContactDtos.ContactView create(UUID ownerId, ContactDtos.ContactRequest request) {
        if (request == null) {
            throw bad("Заполните контакт");
        }
        String email = normalizeEmail(request.email());
        String displayName = displayName(request.displayName(), email);
        contacts.findByOwnerIdAndEmailIgnoreCase(ownerId, email).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Контакт с таким адресом уже есть");
        });
        if (contacts.countByOwnerId(ownerId) >= MAX_CONTACTS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Достигнут лимит адресной книги: " + MAX_CONTACTS + " контактов");
        }
        Contact contact = new Contact();
        contact.setOwnerId(ownerId);
        contact.setSource("MANUAL");
        apply(contact, displayName, email, request.phone(), request.organization(),
                request.jobTitle(), request.note(), request.groupName());
        return toView(contacts.saveAndFlush(contact));
    }

    @Transactional
    public ContactDtos.ContactView update(UUID ownerId, UUID contactId, ContactDtos.ContactRequest request) {
        if (request == null) {
            throw bad("Заполните контакт");
        }
        Contact contact = owned(ownerId, contactId);
        String email = normalizeEmail(request.email());
        contacts.findByOwnerIdAndEmailIgnoreCase(ownerId, email)
                .filter(other -> !other.getId().equals(contact.getId()))
                .ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Контакт с таким адресом уже есть");
                });
        apply(contact, displayName(request.displayName(), email), email, request.phone(),
                request.organization(), request.jobTitle(), request.note(), request.groupName());
        return toView(contacts.saveAndFlush(contact));
    }

    @Transactional
    public void delete(UUID ownerId, UUID contactId) {
        contacts.delete(owned(ownerId, contactId));
    }

    @Transactional
    public ContactDtos.ImportResult importContacts(UUID ownerId, String fileName, byte[] bytes,
                                                   ContactImportParser.Format format) {
        if (bytes == null || bytes.length == 0) {
            throw bad("Файл пуст");
        }
        if (bytes.length > MAX_IMPORT_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Файл больше " + (MAX_IMPORT_BYTES / (1024 * 1024)) + " МиБ");
        }
        ContactImportParser.Result parsed = ContactImportParser.parse(fileName, bytes, format);
        Map<String, Contact> existing = new HashMap<>();
        for (Contact contact : contacts.findAllByOwnerIdOrderByDisplayNameAsc(ownerId)) {
            existing.putIfAbsent(contact.getEmail().toLowerCase(Locale.ROOT), contact);
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;
        boolean truncated = false;
        List<ContactDtos.ImportIssueView> issues = new ArrayList<>();
        for (ImportIssue issue : parsed.issues()) {
            addIssue(issues, issue.line(), issue.value(), issue.reason());
        }

        for (ParsedContact candidate : parsed.contacts()) {
            String email = tryNormalizeEmail(candidate.email());
            if (email == null) {
                skipped++;
                addIssue(issues, candidate.line(), candidate.email(), "некорректный адрес электронной почты");
                continue;
            }
            String key = email.toLowerCase(Locale.ROOT);
            Contact current = existing.get(key);
            if (current == null) {
                if (existing.size() >= MAX_CONTACTS) {
                    truncated = true;
                    addIssue(issues, candidate.line(), email,
                            "достигнут лимит адресной книги, остаток файла не импортирован");
                    break;
                }
                Contact contact = new Contact();
                contact.setOwnerId(ownerId);
                contact.setSource(parsed.format().name());
                apply(contact, displayName(candidate.displayName(), email), email, candidate.phone(),
                        candidate.organization(), candidate.jobTitle(), candidate.note(), candidate.groupName());
                Contact saved = contacts.save(contact);
                existing.put(key, saved);
                created++;
                continue;
            }
            if (mergeMissing(current, candidate)) {
                contacts.save(current);
                updated++;
            } else {
                skipped++;
            }
        }
        contacts.flush();
        return new ContactDtos.ImportResult(parsed.contacts().size() + parsed.issues().size(),
                created, updated, skipped, truncated, issues);
    }

    @Transactional(readOnly = true)
    public Export export(UUID ownerId, String format) {
        List<Contact> all = contacts.findAllByOwnerIdOrderByDisplayNameAsc(ownerId);
        if ("csv".equalsIgnoreCase(format)) {
            StringBuilder csv = new StringBuilder();
            csv.append('\uFEFF').append("Name,E-mail,Phone,Organization,Job title,Note,Group\r\n");
            for (Contact contact : all) {
                csv.append(csvCell(contact.getDisplayName())).append(',')
                        .append(csvCell(contact.getEmail())).append(',')
                        .append(csvCell(contact.getPhone())).append(',')
                        .append(csvCell(contact.getOrganization())).append(',')
                        .append(csvCell(contact.getJobTitle())).append(',')
                        .append(csvCell(contact.getNote())).append(',')
                        .append(csvCell(contact.getGroupName())).append("\r\n");
            }
            return new Export(csv.toString().getBytes(StandardCharsets.UTF_8),
                    "corvex-contacts.csv", "text/csv; charset=UTF-8");
        }
        StringBuilder vcf = new StringBuilder();
        for (Contact contact : all) {
            vcf.append("BEGIN:VCARD\r\nVERSION:3.0\r\n");
            vcf.append("FN:").append(vcardEscaped(contact.getDisplayName())).append("\r\n");
            vcf.append("EMAIL;TYPE=INTERNET:").append(vcardEscaped(contact.getEmail())).append("\r\n");
            appendVcard(vcf, "TEL;TYPE=VOICE", contact.getPhone());
            appendVcard(vcf, "ORG", contact.getOrganization());
            appendVcard(vcf, "TITLE", contact.getJobTitle());
            appendVcard(vcf, "CATEGORIES", contact.getGroupName());
            appendVcard(vcf, "NOTE", contact.getNote());
            vcf.append("END:VCARD\r\n");
        }
        return new Export(vcf.toString().getBytes(StandardCharsets.UTF_8),
                "corvex-contacts.vcf", "text/vcard; charset=UTF-8");
    }

    public record Export(byte[] content, String fileName, String contentType) {
    }

    // ------------------------------------------------------------------ helpers

    private Contact owned(UUID ownerId, UUID contactId) {
        return contacts.findByIdAndOwnerId(contactId, ownerId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Контакт не найден"));
    }

    private static void apply(Contact contact, String displayName, String email, String phone,
                              String organization, String jobTitle, String note, String groupName) {
        contact.setDisplayName(displayName);
        contact.setEmail(email);
        contact.setPhone(trimToNull(phone, 64));
        contact.setOrganization(trimToNull(organization, 256));
        contact.setJobTitle(trimToNull(jobTitle, 256));
        contact.setNote(trimToNull(note, 4000));
        contact.setGroupName(trimToNull(groupName, 128));
    }

    /** Дополняет только пустые поля; true — если контакт изменился. */
    private static boolean mergeMissing(Contact contact, ParsedContact candidate) {
        boolean changed = false;
        if (isBlank(contact.getDisplayName())) {
            contact.setDisplayName(displayName(candidate.displayName(), contact.getEmail()));
            changed = true;
        }
        if (isBlank(contact.getPhone()) && !isBlank(candidate.phone())) {
            contact.setPhone(trimToNull(candidate.phone(), 64));
            changed = true;
        }
        if (isBlank(contact.getOrganization()) && !isBlank(candidate.organization())) {
            contact.setOrganization(trimToNull(candidate.organization(), 256));
            changed = true;
        }
        if (isBlank(contact.getJobTitle()) && !isBlank(candidate.jobTitle())) {
            contact.setJobTitle(trimToNull(candidate.jobTitle(), 256));
            changed = true;
        }
        if (isBlank(contact.getNote()) && !isBlank(candidate.note())) {
            contact.setNote(trimToNull(candidate.note(), 4000));
            changed = true;
        }
        if (isBlank(contact.getGroupName()) && !isBlank(candidate.groupName())) {
            contact.setGroupName(trimToNull(candidate.groupName(), 128));
            changed = true;
        }
        return changed;
    }

    private static void addIssue(List<ContactDtos.ImportIssueView> issues, int line, String value, String reason) {
        if (issues.size() >= MAX_IMPORT_ISSUES) {
            return;
        }
        issues.add(new ContactDtos.ImportIssueView(line, truncate(value, 120), truncate(reason, 200)));
    }

    private static String displayName(String requested, String email) {
        String value = requested == null ? "" : requested.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (!value.isBlank()) {
            return truncate(value, 256);
        }
        int at = email.indexOf('@');
        return truncate(at > 0 ? email.substring(0, at) : email, 256);
    }

    /** Мягкая нормализация для импорта: непригодный адрес становится строкой отчёта, а не 400. */
    static String tryNormalizeEmail(String raw) {
        String value = normalizeAddress(raw);
        return value != null && EMAIL.matcher(value).matches() && value.length() <= 320 ? value : null;
    }

    private static String normalizeAddress(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.replace("\uFEFF", "").trim();
        int angle = value.indexOf('<');
        if (angle >= 0) {
            int close = value.indexOf('>', angle);
            value = close > angle ? value.substring(angle + 1, close) : value.substring(angle + 1);
            value = value.trim();
        }
        if (value.toLowerCase(Locale.ROOT).startsWith("mailto:")) {
            value = value.substring("mailto:".length()).trim();
        }
        return value.isBlank() ? null : value;
    }

    static String normalizeEmail(String raw) {
        String value = normalizeAddress(raw);
        if (value == null) {
            throw bad("Укажите адрес электронной почты");
        }
        if (!EMAIL.matcher(value).matches()) {
            throw bad("Некорректный адрес электронной почты: " + truncate(value, 120));
        }
        if (value.length() > 320) {
            throw bad("Адрес электронной почты длиннее 320 символов");
        }
        return value;
    }

    private static String trimToNull(String value, int max) {
        if (value == null) {
            return null;
        }
        String clean = value.replace("\u0000", "").replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.isBlank() ? null : truncate(clean, max);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replace("\r\n", "\n").replace('\r', '\n');
        if (clean.contains(",") || clean.contains("\"") || clean.contains("\n") || clean.contains(";")) {
            return "\"" + clean.replace("\"", "\"\"") + "\"";
        }
        return clean;
    }

    private static void appendVcard(StringBuilder builder, String property, String value) {
        if (!isBlank(value)) {
            builder.append(property).append(':').append(vcardEscaped(value)).append("\r\n");
        }
    }

    static String vcardEscaped(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case ';' -> out.append("\\;");
                case ',' -> out.append("\\,");
                case '\r' -> { }
                case '\n' -> out.append("\\n");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static ContactDtos.ContactView toView(Contact contact) {
        return new ContactDtos.ContactView(
                contact.getId(), contact.getDisplayName(), contact.getEmail(), contact.getPhone(),
                contact.getOrganization(), contact.getJobTitle(), contact.getNote(),
                contact.getGroupName(), contact.getSource(), contact.getCreatedAt(), contact.getUpdatedAt());
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
