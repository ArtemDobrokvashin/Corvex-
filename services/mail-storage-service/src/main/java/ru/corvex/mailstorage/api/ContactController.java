package ru.corvex.mailstorage.api;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.ContactDtos;
import ru.corvex.mailstorage.contacts.ContactImportParser;
import ru.corvex.mailstorage.contacts.ContactService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Адресная книга сотрудника. Право одно — MAIL_READ_OWN: контакты принадлежат владельцу
 * ящика, отдельного права на них нет, как нет и доступа администратора к чужой книге.
 */
@RestController
@RequestMapping("/api/v1/mail/contacts")
public class ContactController {

    private final ContactService contacts;

    public ContactController(ContactService contacts) {
        this.contacts = contacts;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    List<ContactDtos.ContactView> list(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) Integer limit) {
        return contacts.list(userId(jwt), q, limit);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    ResponseEntity<ContactDtos.ContactView> create(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestBody ContactDtos.ContactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contacts.create(userId(jwt), request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    ContactDtos.ContactView update(@AuthenticationPrincipal Jwt jwt,
                                   @PathVariable UUID id,
                                   @RequestBody ContactDtos.ContactRequest request) {
        return contacts.update(userId(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        contacts.delete(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    ContactDtos.ImportResult importFile(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam("file") MultipartFile file,
                                        @RequestParam(defaultValue = "AUTO") String format) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл пуст");
        }
        ContactImportParser.Format parsed;
        try {
            parsed = ContactImportParser.Format.valueOf(format.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Неизвестный формат: используйте AUTO, VCARD или CSV");
        }
        return contacts.importContacts(userId(jwt), file.getOriginalFilename(), file.getBytes(), parsed);
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    ResponseEntity<byte[]> export(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(defaultValue = "vcf") String format) {
        ContactService.Export export = contacts.export(userId(jwt), format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, export.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.fileName(), StandardCharsets.UTF_8)
                        .build().toString())
                .body(export.content());
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "JWT subject не является UUID пользователя");
        }
    }
}
