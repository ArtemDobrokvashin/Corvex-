package ru.corvex.chatfile.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFileSecurityConfigTest {

    private static final UUID USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final ChatFileSecurityConfig config = new ChatFileSecurityConfig();

    @Test
    void jwtConverterMapsPermissionsClaimDirectlyToAuthorities() {
        JwtAuthenticationConverter converter = config.jwtAuthenticationConverter();

        var authentication = converter.convert(jwt(List.of(
                ChatFilePrincipal.READ,
                ChatFilePrincipal.SEND_FILES)));

        assertEquals(USER_ID.toString(), authentication.getName());
        assertEquals(Set.of(ChatFilePrincipal.READ, ChatFilePrincipal.SEND_FILES),
                authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet()));
    }

    @Test
    void jwtConverterProducesNoAuthoritiesWithoutPermissionsClaim() {
        JwtAuthenticationConverter converter = config.jwtAuthenticationConverter();

        var authentication = converter.convert(jwt(null));

        assertTrue(authentication.getAuthorities().isEmpty());
    }

    @Test
    void jwtDecoderIsBuiltForConfiguredJwkSetUri() {
        JwtDecoder decoder = config.jwtDecoder("http://127.0.0.1:65535/oauth2/jwks");

        assertInstanceOf(NimbusJwtDecoder.class, decoder);
    }

    private Jwt jwt(List<String> permissions) {
        Instant now = Instant.parse("2026-10-23T07:00:00Z");
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900));
        if (permissions != null) {
            builder.claim("permissions", permissions);
        }
        return builder.build();
    }
}
