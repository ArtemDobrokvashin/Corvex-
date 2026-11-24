package ru.corvex.mailstorage.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.mailstorage.settings.MailSettings;
import ru.corvex.mailstorage.settings.MailSettingsService;

import java.util.UUID;

/** Настройки почты за пользователем: одно и то же на всех его устройствах. */
@RestController
@RequestMapping("/api/v1/mail/settings")
public class MailSettingsController {

    private final MailSettingsService settings;

    public MailSettingsController(MailSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    MailSettings get(@AuthenticationPrincipal Jwt jwt) {
        return settings.get(userId(jwt));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    MailSettings save(@AuthenticationPrincipal Jwt jwt, @RequestBody MailSettings body) {
        return settings.save(userId(jwt), body);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
