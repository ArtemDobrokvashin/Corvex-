package ru.corvex.auth.security;

import com.Nimbusds.jose.jwk.JWKSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Безопасность auth-service и JWKS-эндпоинт, на который ссылаются gateway и все сервисы
 * (CORVEX_JWK_SET_URI=http://auth-service:8081/oauth2/jwks).
 * Публичны: вход/refresh/подтверждение 2FA/logout (там входных токенов ещё нет),
 * JWKS, healthcheck и /internal/** (последний защищён InternalApiTokenFilter; gateway его не маршрутизирует).
 */
@Configuration
public class AuthSecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // API: Bearer + refresh-cookie SameSite=Strict
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/login", "/api/v1/auth/mfa/verify",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/internal/**", "/oauth2/jwks", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
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

    /**
     * Argon2id (параметры Spring Security 5.8+ по умолчанию). Требует BouncyCastle
     * на classpath (bcprov-jdk18on в pom) — иначе упадёт при первом хэшировании.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @RestController
    static class JwksController {
        private final JWKSet jwkSet;

        JwksController(JWKSet jwkSet) {
            this.jwkSet = jwkSet;
        }

        @GetMapping(value = "/oauth2/jwks", produces = "application/json")
        Map<String, Object> jwks() {
            return jwkSet.toJSONObject();
        }
    }
}
