package ru.corvex.chatfile.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Нормализованная identity владельца JWT для файлового API.
 * Права берутся из того же claim {@code permissions}, что использует chat-service.
 */
public record ChatFilePrincipal(UUID userId, Set<String> permissions) {

    public static final String READ = "CHAT_READ";
    public static final String WRITE = "CHAT_WRITE";
    public static final String MODERATE = "CHAT_MODERATE";
    public static final String SEND_FILES = "CHAT_SEND_FILES";

    public ChatFilePrincipal {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public static ChatFilePrincipal from(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Отсутствует JWT пользователя");
        }
        List<String> claims = jwt.getClaimAsStringList("permissions");
        return new ChatFilePrincipal(
                parseUserId(jwt.getSubject()),
                claims == null ? Set.of() : Set.copyOf(claims));
    }

    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    public void require(String permission) {
        if (!has(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Нет права " + permission);
        }
    }

    private static UUID parseUserId(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "JWT subject не является UUID пользователя");
        }
    }
}
