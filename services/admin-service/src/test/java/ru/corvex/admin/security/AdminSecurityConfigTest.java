package ru.corvex.admin.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AdminSecurityConfigTest {

    private final AdminSecurityConfig config = new AdminSecurityConfig();

    @Test
    void permissionsClaimBecomesGrantedAuthorities() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-id")
                .claim("permissions", List.of("ADMIN_USERS_MANAGE", "AUDIT_VIEW"))
                .build();

        var authentication = config.jwtAuthenticationConverter().convert(jwt);

        assertEquals(List.of("ADMIN_USERS_MANAGE", "AUDIT_VIEW"),
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
    }

    @Test
    void missingOrEmptyPermissionsClaimProducesNoAuthorities() {
        Jwt missing = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-id")
                .build();
        Jwt empty = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-id")
                .claim("permissions", List.of())
                .build();

        assertEquals(List.of(), config.jwtAuthenticationConverter().convert(missing).getAuthorities());
        assertEquals(List.of(), config.jwtAuthenticationConverter().convert(empty).getAuthorities());
    }

    @Test
    void localeResolverDefaultsToRussianSupportsEnglishAndUsesAcceptLanguage() {
        var resolver = assertInstanceOf(
                AcceptHeaderLocaleResolver.class, config.localeResolver());
        MockHttpServletRequest english = new MockHttpServletRequest();
        english.addHeader("Accept-Language", "en-US,en;q=0.9");
        MockHttpServletRequest unsupported = new MockHttpServletRequest();
        unsupported.addHeader("Accept-Language", "de-DE");

        assertEquals(Locale.forLanguageTag("ru"), resolver.getDefaultLocale());
        assertEquals(List.of(Locale.forLanguageTag("ru"), Locale.ENGLISH),
                resolver.getSupportedLocales());
        assertEquals(Locale.ENGLISH, resolver.resolveLocale(english));
        assertEquals(Locale.forLanguageTag("ru"), resolver.resolveLocale(unsupported));
    }
}
