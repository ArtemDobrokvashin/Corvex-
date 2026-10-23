package ru.corvex.chat.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSecurityConfigTest {

    private final ChatSecurityConfig config = new ChatSecurityConfig();

    @Test
    void converterMapsPermissionsClaimToAuthorities() {
        JwtAuthenticationConverter converter = config.jwtAuthenticationConverter();
        Jwt jwt = jwt(List.of(ChatPrincipal.READ, ChatPrincipal.MODERATE));

        JwtAuthenticationToken authentication = (JwtAuthenticationToken) converter.convert(jwt);

        assertNotNull(authentication);
        assertEquals(List.of(ChatPrincipal.READ, ChatPrincipal.MODERATE),
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
        assertEquals(jwt, authentication.getToken());
    }

    @Test
    void converterProducesNoAuthoritiesWhenPermissionsClaimMissing() {
        JwtAuthenticationConverter converter = config.jwtAuthenticationConverter();
        JwtAuthenticationToken authentication =
                (JwtAuthenticationToken) converter.convert(jwt(null));

        assertNotNull(authentication);
        assertTrue(authentication.getAuthorities().isEmpty());
    }

    @Test
    void decoderCanBeConstructedForConfiguredJwksUri() {
        assertNotNull(config.jwtDecoder("http://127.0.0.1:9/oauth2/jwks"));
    }

    private Jwt jwt(List<String> permissions) {
        Instant now = Instant.parse("2026-10-20T09:00:00Z");
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900));
        if (permissions != null) {
            builder.claim("permissions", permissions);
        }
        return builder.build();
    }
}
