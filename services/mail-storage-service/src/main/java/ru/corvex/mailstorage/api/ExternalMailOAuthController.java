package ru.corvex.mailstorage.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.ExternalMailOAuthDtos;
import ru.corvex.mailstorage.external.ExternalMailOAuthService;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Вход в Gmail и Microsoft 365 через OAuth2.
 *
 * <p>Старт — обычный защищённый вызов: он выдаёт одноразовый state и адрес согласия.
 * Callback приходит из браузера после редиректа провайдера и поэтому открыт: доверие
 * строится на state, а не на cookie или заголовке.
 */
@RestController
@RequestMapping("/api/v1/mail/external-accounts/oauth")
public class ExternalMailOAuthController {

    private static final String CAN_READ = "hasAnyAuthority('MAIL_EXTERNAL_ACCOUNTS','ADMIN_MAIL_POLICY')";

    private final ExternalMailOAuthService oauth;

    public ExternalMailOAuthController(ExternalMailOAuthService oauth) {
        this.oauth = oauth;
    }

    @GetMapping("/providers")
    @PreAuthorize("hasAnyAuthority('MAIL_EXTERNAL_ACCOUNTS','ADMIN_MAIL_POLICY','MAIL_READ_OWN')")
    List<ExternalMailOAuthDtos.ProviderView> providers() {
        return oauth.providers();
    }

    @PostMapping("/authorize")
    @PreAuthorize(CAN_READ)
    ExternalMailOAuthDtos.AuthorizeResponse authorize(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestBody(required = false) ExternalMailOAuthDtos.AuthorizeRequest request) {
        return oauth.start(userId(jwt), permissions(jwt), request);
    }

    /**
     * Возврат из Google/Microsoft. Отвечает 302 на адрес приложения: и успех, и отказ
     * показываются в мастере, а не страницей провайдера.
     */
    @GetMapping("/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                  @RequestParam(required = false) String state,
                                  @RequestParam(required = false) String error) {
        String location = oauth.callback(code, state, error);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    @GetMapping("/connections/{connectionId}")
    @PreAuthorize(CAN_READ)
    ExternalMailOAuthDtos.ConnectionView connection(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable UUID connectionId) {
        return oauth.connection(userId(jwt), permissions(jwt), connectionId);
    }

    private static Set<String> permissions(Jwt jwt) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        return permissions == null ? Set.of() : new HashSet<>(permissions);
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT subject не является UUID пользователя");
        }
    }
}
