package ru.corvex.mailsmtp.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.mailsmtp.outbound.DkimSigner;

/**
 * Отдаёт только публичный ключ подписи, чтобы админский API доменов мог
 * сформировать DKIM-запись и проверить, что опубликован именно ключ сервера.
 *
 * <p>Порт 8082 не публикуется наружу; кроме того, запрос требует внутренний
 * токен. Пустой токен в конфигурации = 503, неверный = 401. Приватный ключ
 * никогда не сериализуется.
 */
@RestController
public class DkimPublicKeyController {

    private final DkimSigner signer;
    private final String expectedToken;

    public DkimPublicKeyController(DkimSigner signer,
            @Value("${corvex.security.internal-token:${CORVEX_INTERNAL_API_TOKEN:}}") String expectedToken) {
        this.signer = signer;
        this.expectedToken = expectedToken;
    }

    @GetMapping("/internal/dkim/public-key")
    public ResponseEntity<Map<String, String>> publicKey(
            @RequestHeader(value = "X-Corvex-Internal-Token", required = false) String token) {
        if (expectedToken == null || expectedToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "internal token is not configured"));
        }
        if (token == null || !MessageDigest.isEqual(expectedToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        String publicKey = signer.publicKeyBase64();
        if (!signer.isEnabled() || publicKey == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "DKIM signing is not configured with an RSA key"));
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("domain", signer.domain());
        body.put("selector", signer.selector());
        body.put("publicKeyBase64", publicKey);
        return ResponseEntity.ok(body);
    }
}
