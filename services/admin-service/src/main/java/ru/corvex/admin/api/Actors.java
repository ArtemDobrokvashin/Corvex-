package ru.corvex.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.corvex.admin.service.AuditService;

import java.util.UUID;

/** Кто выполняет административное действие — для аудита (id/имя из JWT, IP с учётом прокси). */
final class Actors {

    private Actors() {
    }

    static AuditService.Actor from(Jwt jwt, HttpServletRequest request) {
        UUID id = null;
        String name = null;
        if (jwt != null) {
            name = jwt.getClaimAsString("preferred_username");
            try {
                id = UUID.fromString(jwt.getSubject());
            } catch (Exception ignored) {
                // id останется null — имя всё равно запишется
            }
        }
        String ip = null;
        if (request != null) {
            String xff = request.getHeader("X-Forwarded-For");
            ip = xff != null && !xff.isBlank() ? xff.split(",")[0].trim() : request.getRemoteAddr();
        }
        return new AuditService.Actor(id, name, ip);
    }

    static UUID userId(Jwt jwt) {
        try {
            return jwt == null ? null : UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            return null;
        }
    }
}
