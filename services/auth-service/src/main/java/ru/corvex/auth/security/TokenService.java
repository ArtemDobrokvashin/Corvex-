package ru.corvex.auth.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Выпуск access-токенов JWT (RS256). Ключевые решения:
 * <ul>
 *   <li>access-токен короткоживущий (15 мин по умолчанию), подпись RSA — сервисы проверяют
 *       токен локально по JWKS (GET /oauth2/jwks), без похода в auth-service на каждый запрос;</li>
 *   <li>refresh-токен — НЕ JWT, а непрозрачные 32 случайных байта: SHA-256-хэш хранится
 *       в PostgreSQL, токен вращается при каждом использовании и отдаётся клиенту только
 *       HttpOnly+Secure+SameSite=Strict cookie;</li>
 *   <li>права (permissions) кладутся в claim — gateway и сервисы авторизуют без запроса в БД,
 *       цена — отзыв прав действует не мгновенно, а в пределах TTL access-токена (документируется!).</li>
 * </ul>
 */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final Duration accessTtl;

    public TokenService(JwtEncoder encoder,
                        @Value("${corvex.security.access-token-ttl:PT15M}") Duration accessTtl) {
        this.encoder = encoder;
        this.accessTtl = accessTtl;
    }

    public record AccessToken(String value, Instant expiresAt) {
    }

    public AccessToken issueAccessToken(UUID userId, String username, Collection<String> groups,
                                        Collection<String> permissions, boolean mfaVerified) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTtl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("corvex-auth")
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())          // jti — для blacklist при форс-отзыве
                .claim("preferred_username", username)
                .claim("groups", groups)                    // коды групп нужны /me и сервисам без похода в admin-service
                .claim("permissions", permissions)          // итоговая сумма прав всех групп пользователя
                .claim("amr", mfaVerified ? java.util.List.of("pwd", "otp") : java.util.List.of("pwd"))
                .build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }
}
