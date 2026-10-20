package ru.corvex.chat.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Нормализованная identity текущего пользователя для REST и STOMP.
 * Источник прав один и тот же — claim {@code permissions} RS256 JWT auth-service.
 */
public record ChatPrincipal(UUID userId, Set<String> permissions, Jwt jwt) {

    public static final String READ = "CHAT_READ";
    public static final String WRITE = "CHAT_WRITE";
    public static final String CREATE_GROUP = "CHAT_CREATE_GROUP";
    public static final String CREATE_CHANNEL = "CHAT_CREATE_CHANNEL";
    public static final String MODERATE = "CHAT_MODERATE";
    public static final String SEND_FILES = "CHAT_SEND_FILES";
    public static final String VOICE_VIDEO = "CHAT_VOICE_VIDEO";

    public ChatPrincipal {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public static ChatPrincipal from(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Отсутствует JWT пользователя");
        }
        List<String> claims = jwt.getClaimAsStringList("permissions");
        return new ChatPrincipal(parseUserId(jwt.getSubject()), claims == null ? Set.of() : Set.copyOf(claims), jwt);
    }

    public static ChatPrincipal from(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            return from(token.getToken());
        }
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return from(jwt);
        }
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не аутентифицирован");
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return new ChatPrincipal(parseUserId(authentication.getName()), authorities, null);
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
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "JWT subject не является UUID пользователя");
        }
    }
}
