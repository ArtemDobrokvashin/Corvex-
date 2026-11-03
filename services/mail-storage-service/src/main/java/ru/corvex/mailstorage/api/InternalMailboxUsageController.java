package ru.corvex.mailstorage.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.mailstorage.api.dto.ProtocolDtos;
import ru.corvex.mailstorage.security.InternalTokenGuard;
import ru.corvex.mailstorage.service.ProtocolMailService;

import java.util.List;

/** Внутренний batch-only usage API; gateway его не маршрутизирует. */
@RestController
@RequestMapping("/internal/mail")
public class InternalMailboxUsageController {

    private final ProtocolMailService service;
    private final InternalTokenGuard tokens;

    public InternalMailboxUsageController(ProtocolMailService service, InternalTokenGuard tokens) {
        this.service = service;
        this.tokens = tokens;
    }

    @PostMapping("/usage")
    List<ProtocolDtos.MailboxUsageView> usage(
            @RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
            @Valid @RequestBody ProtocolDtos.UsageRequest request) {
        tokens.verify(token);
        return service.usage(request.ownerIds());
    }
}
