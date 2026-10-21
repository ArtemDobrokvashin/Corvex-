package ru.corvex.mailstorage.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.mailstorage.api.dto.ExternalMailDtos;
import ru.corvex.mailstorage.external.ExternalMailAccountService;
import ru.corvex.mailstorage.security.InternalTokenGuard;

import java.util.UUID;

/**
 * Internal API для mail-smtp-service. Расшифрованный секрет отдаётся только по X-Corvex-Internal-Token
 * в момент отправки и не кладётся в RabbitMQ-события.
 */
@RestController
@RequestMapping("/internal/external-mail/accounts")
public class InternalExternalMailController {

    private final ExternalMailAccountService accounts;
    private final InternalTokenGuard tokens;

    public InternalExternalMailController(ExternalMailAccountService accounts, InternalTokenGuard tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @GetMapping("/{id}/transport-config")
    ExternalMailDtos.TransportConfigView transportConfig(
            @RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
            @PathVariable UUID id) {
        tokens.verify(token);
        return accounts.transportConfig(id);
    }
}
