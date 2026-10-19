package ru.corvex.admin.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * Авторизация admin-service: JWT проверяется по JWKS auth-service (decoder
 * автонастраивается Boot'ом из spring.security.oauth2.resourceserver.jwt.jwk-set-uri),
 * а authorities берутся из claim {@code permissions} — так работают @PreAuthorize
 * с кодами прав (ADMIN_USERS_MANAGE и т.д.). /internal/** дополнительно защищён сервисным токеном.
 */
@Configuration
@EnableMethodSecurity
public class AdminSecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/internal/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /** Публичный API говорит на русском по умолчанию и на английском по Accept-Language. */
    @Bean
    LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.forLanguageTag("ru"));
        resolver.setSupportedLocales(List.of(Locale.forLanguageTag("ru"), Locale.ENGLISH));
        return resolver;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> permissions = jwt.getClaimAsStringList("permissions");
            if (permissions == null) {
                return List.of();
            }
            return permissions.stream()
                    .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p))
                    .toList();
        });
        return converter;
    }
}
