package ru.corvex.mailstorage.api.dto;

import java.time.Instant;
import java.util.UUID;

/** Контракты входа через Google и Microsoft 365 для внешних ящиков и переноса почты. */
public final class ExternalMailOAuthDtos {

    private ExternalMailOAuthDtos() {
    }

    /** Что показать в мастере: доступен ли провайдер и почему нет. */
    public record ProviderView(String provider, String title, boolean enabled, String hint) {
    }

    /** provider — GOOGLE или MICROSOFT; targetUserId заполняет администратор для сотрудника. */
    public record AuthorizeRequest(String provider, UUID targetUserId, String returnPath) {
    }

    /** Адрес, на который браузер уходит за согласием; state нужен только для журнала. */
    public record AuthorizeResponse(String authorizationUrl, String state, Instant expiresAt) {
    }

    /** Итог согласия до создания внешнего ящика: адрес уже проверен провайдером. */
    public record ConnectionView(UUID id, String provider, String email, Instant expiresAt) {
    }
}
