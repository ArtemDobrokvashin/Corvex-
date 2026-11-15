package ru.corvex.mailstorage.maildomain;

import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Админский API собственных доменов. Доступ — право ADMIN_MAIL_POLICY
 * (домены, спам/антивирус, лимиты вложений), без него — 403.
 *
 * <p>POST /{domain}/james отвечает 202, если исход записи в James неизвестен:
 * клиент не должен трактовать это как успех и повторять запрос.
 */
@RestController
@RequestMapping("/api/v1/mail/admin/domains")
public class MailDomainController {

    private final MailDomainService service;

    public MailDomainController(MailDomainService service) {
        this.service = service;
    }

    public record AddRequest(String domain) { }

    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN_MAIL_POLICY')")
    ResponseEntity<List<MailDomainService.View>> list() {
        return ok(service.list());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_MAIL_POLICY')")
    ResponseEntity<MailDomainService.View> add(@AuthenticationPrincipal Jwt jwt,
                                               @RequestBody(required = false) AddRequest request) {
        String domain = request == null ? null : request.domain();
        if (domain == null || domain.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Домен не задан");
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.register(domain, userId(jwt)));
    }

    @GetMapping("/{domain}")
    @PreAuthorize("hasAuthority('ADMIN_MAIL_POLICY')")
    ResponseEntity<MailDomainService.View> get(@PathVariable String domain) {
        return ok(service.get(domain));
    }

    @PostMapping("/{domain}/james")
    @PreAuthorize("hasAuthority('ADMIN_MAIL_POLICY')")
    ResponseEntity<MailDomainService.View> addToJames(@AuthenticationPrincipal Jwt jwt,
                                                      @PathVariable String domain) {
        MailDomainService.JamesAttempt attempt = service.addToJames(domain, userId(jwt));
        return ResponseEntity.status(attempt.uncertain() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(attempt.view());
    }

    @PostMapping("/{domain}/verify")
    @PreAuthorize("hasAuthority('ADMIN_MAIL_POLICY')")
    ResponseEntity<MailDomainService.View> verify(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable String domain) {
        return ok(service.verify(domain, userId(jwt)));
    }

    @GetMapping("/{domain}/dns")
    @PreAuthorize("hasAuthority('ADMIN_MAIL_POLICY')")
    ResponseEntity<List<MailDomainService.DnsInstruction>> dns(@PathVariable String domain) {
        return ok(service.dnsInstructions(domain));
    }

    /** Удаляет только запись реестра; домен и ящики в James не изменяются. */
    @DeleteMapping("/{domain}")
    @PreAuthorize("hasAuthority('ADMIN_MAIL_POLICY')")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String domain) {
        service.delete(domain, userId(jwt));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT subject не является UUID пользователя");
        }
    }
}
