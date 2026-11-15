package ru.corvex.mailsmtp.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.mailsmtp.outbound.DkimKeyRing;
import ru.corvex.mailsmtp.outbound.DkimSigner;

/**
 * Отдаёт только публичные ключи подписи, чтобы админский API доменов мог
 * сформировать DKIM-запись и проверить, что опубликован именно ключ сервера.
 *
 * <p>Порт 8082 не публикуется наружу; кроме того, запрос требует внутренний
 * токен. Пустой токен в конфигурации = 503, неверный = 401. Приватные ключи
 * никогда не сериализуются.
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

    /**
     * Ключ конкретного домена. Домен без ключа — 404, а не чужой ключ: иначе
     * проверка на стороне storage сравнила бы DNS-запись с неподходящим ключом.
     */
    @GetMapping("/internal/dkim/public-key")
    public ResponseEntity<Map<String, String>> publicKey(
            @RequestHeader(value = "X-Corvex-Internal-Token", required = false) String token,
            @RequestParam(value = "domain", required = false) String domain) {
        ResponseEntity<Map<String, String>> unauthorized = authorize(token);
        if (unauthorized != null) {
            return unauthorized;
        }
        DkimKeyRing ring = signer.keyRing();
        DkimKeyRing.Key key = domain == null || domain.isBlank()
                ? singleKey(ring)
                : ring.find(domain).orElse(null);
        if (key == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "no DKIM key for this domain"));
        }
        return ResponseEntity.ok(body(key));
    }

    /** Полный список доменов, для которых сервер подписывает письма. */
    @GetMapping("/internal/dkim/keys")
    public ResponseEntity<?> keys(
            @RequestHeader(value = "X-Corvex-Internal-Token", required = false) String token) {
        ResponseEntity<Map<String, String>> unauthorized = authorize(token);
        if (unauthorized != null) {
            return unauthorized;
        }
        List<Map<String, String>> keys = signer.keyRing().keys().stream().map(this::body).toList();
        return ResponseEntity.ok(Map.of("keys", keys));
    }

    private ResponseEntity<Map<String, String>> authorize(String token) {
        if (expectedToken == null || expectedToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "internal token is not configured"));
        }
        if (token == null || !MessageDigest.isEqual(expectedToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        return null;
    }

    private static DkimKeyRing.Key singleKey(DkimKeyRing ring) {
        // Без параметра поддерживается прежний контракт: ровно один домен.
        List<DkimKeyRing.Key> keys = ring.keys();
        return keys.size() == 1 ? keys.get(0) : null;
    }

    private Map<String, String> body(DkimKeyRing.Key key) {
        String publicKey = key.publicKeyBase64();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("domain", key.domain());
        body.put("selector", key.selector());
        if (publicKey == null) {
            body.put("error", "only RSA keys can be published");
            return body;
        }
        body.put("publicKeyBase64", publicKey);
        return body;
    }
}
