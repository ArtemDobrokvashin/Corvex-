package ru.corvex.mailstorage.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.OnboardingDtos;
import ru.corvex.mailstorage.onboarding.OnboardingService;

import java.util.UUID;

/**
 * Онбординг после первого входа. Состояние персональное: админ его не читает и не меняет,
 * чтобы «предложение переноса» нельзя было отключить за сотрудника незаметно.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private static final String CAN_READ = "hasAuthority('MAIL_READ_OWN')";

    private final OnboardingService onboarding;

    public OnboardingController(OnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    @GetMapping
    @PreAuthorize(CAN_READ)
    OnboardingDtos.OnboardingView state(@AuthenticationPrincipal Jwt jwt) {
        return onboarding.state(userId(jwt));
    }

    @PostMapping("/migration/dismiss")
    @PreAuthorize(CAN_READ)
    OnboardingDtos.OnboardingView dismiss(@AuthenticationPrincipal Jwt jwt) {
        return onboarding.dismiss(userId(jwt));
    }

    @PostMapping("/migration/not-needed")
    @PreAuthorize(CAN_READ)
    OnboardingDtos.OnboardingView notNeeded(@AuthenticationPrincipal Jwt jwt) {
        return onboarding.notNeeded(userId(jwt));
    }

    /** Вернуть предложение, если сотрудник отложил его навсегда по ошибке. */
    @PostMapping("/migration/reset")
    @PreAuthorize(CAN_READ)
    OnboardingDtos.OnboardingView reset(@AuthenticationPrincipal Jwt jwt) {
        return onboarding.reset(userId(jwt));
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
