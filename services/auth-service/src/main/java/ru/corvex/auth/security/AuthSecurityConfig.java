package ru.corvex.auth.security;

import com.Nimbusds.jose.jwk.JWKSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Безопасность auth-service и JWKS-эндпоинт, на который ссылаются gateway и все сервисы
 * (CORVEX_JWK_SET_URI=http://auth-service:8081/oauth2/jwks). Без этого класса приложение
 * стартовало бы с дефолтной form-login защитой Spring Security и закрытым healthcheck.
 */
@Configuration
public class AuthSecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // API: Bearer + refresh-cookie SameSite=Strict
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/oauth2/jwks", "/api/v1/auth/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
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
