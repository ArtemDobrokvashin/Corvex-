package ru.corvex.mailstorage.api;

import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.mailstorage.maildomain.AcceptedDomains;
import ru.corvex.mailstorage.security.InternalTokenGuard;

/**
 * Внутренний список принимаемых доменов для SMTP/IMAP/POP3.
 *
 * <p>Только внутренний токен, gateway сюда не маршрутизирует. Ответ содержит
 * статические и подтверждённые реестровые домены; если реестр прочитать не
 * удалось, это видно по {@code registryLoadedAt = null}, чтобы вызывающая
 * сторона могла отличить «домена нет» от «список недоступен».
 */
@RestController
@RequestMapping("/internal/mail/domains")
public class InternalMailDomainController {

    private final AcceptedDomains accepted;
    private final InternalTokenGuard tokens;

    public InternalMailDomainController(AcceptedDomains accepted, InternalTokenGuard tokens) {
        this.accepted = accepted;
        this.tokens = tokens;
    }

    public record Response(List<String> domains, Instant registryLoadedAt) { }

    @GetMapping("/accepted")
    Response accepted(@RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token) {
        tokens.verify(token);
        // Первый запрос после старта не должен возвращать «пустой реестр»:
        // иначе SMTP временно откажет домену, который уже подтверждён в James.
        accepted.ensureFresh();
        Instant loadedAt = accepted.loadedAt();
        List<String> all = java.util.stream.Stream.concat(
                        accepted.configuredDomains().stream(), accepted.dynamicDomains().stream())
                .distinct()
                .sorted()
                .toList();
        return new Response(all, loadedAt);
    }
}
