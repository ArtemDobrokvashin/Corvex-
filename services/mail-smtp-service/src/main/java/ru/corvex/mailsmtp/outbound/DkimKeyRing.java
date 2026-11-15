package ru.corvex.mailsmtp.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Набор ключей DKIM по доменам. Один домен — один ключ и селектор; выбор
 * происходит по домену From, поэтому исправление «подписываем только первый
 * домен» не требует менять логику подписи.
 *
 * <p>Источники: явно заданный ключ (`corvex.dkim.domain` +
 * `private-key-file`, обратная совместимость) и каталог, где файл
 * `<domain>.pem` — приватный PKCS#8, а необязательный `<domain>.selector` —
 * селектор этого домена. Каталог читается на старте: ротация ключа требует
 * перезапуска сервиса, что видно в документации и не является неявным
 * поведением.
 *
 * <p>Дубликат домена из двух источников не выбирается «молча»: явный ключ
 * остаётся действующим, а расхождение пишется в лог уровнем warn.
 */
public final class DkimKeyRing {

    private static final Logger log = LoggerFactory.getLogger(DkimKeyRing.class);
    private static final Pattern DOMAIN_FILE = Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    private static final Pattern SELECTOR = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    public record Key(String domain, String selector, PrivateKey privateKey) {

        /**
         * Публичная часть в base64 (X.509 SubjectPublicKeyInfo) — именно она
         * публикуется в DNS как p=. Приватный ключ не покидает сервис.
         */
        public String publicKeyBase64() {
            if (!(privateKey instanceof RSAPrivateCrtKey rsa)) {
                return null;
            }
            try {
                PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                        new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
                return Base64.getEncoder().encodeToString(publicKey.getEncoded());
            } catch (Exception e) {
                return null;
            }
        }
    }

    private final Map<String, Key> keys;

    private DkimKeyRing(Map<String, Key> keys) {
        this.keys = Map.copyOf(keys);
    }

    public static DkimKeyRing empty() {
        return new DkimKeyRing(Map.of());
    }

    /** Явный набор ключей (тесты и будущая загрузка из внешнего хранилища). */
    public static DkimKeyRing of(List<Key> keys) {
        Map<String, Key> map = new LinkedHashMap<>();
        for (Key key : keys) {
            String domain = firstDomain(key.domain());
            if (domain.isEmpty()) {
                continue;
            }
            map.put(domain, new Key(domain, key.selector(), key.privateKey()));
        }
        return new DkimKeyRing(map);
    }

    public static DkimKeyRing single(String domain, String selector, PrivateKey key) {
        Map<String, Key> map = new LinkedHashMap<>();
        if (key != null) {
            String first = firstDomain(domain);
            if (!first.isEmpty()) {
                map.put(first, new Key(first, selector, key));
            }
        }
        return new DkimKeyRing(map);
    }

    /**
     * @param legacyKeyFile файл из старой конфигурации; может отсутствовать
     * @param directory     каталог `<domain>.pem`; может отсутствовать
     */
    public static DkimKeyRing load(String legacyDomain, String legacySelector, String legacyKeyFile,
                                   String directory) {
        Map<String, Key> map = new LinkedHashMap<>();
        String domain = firstDomain(legacyDomain);
        if (legacyKeyFile != null && !legacyKeyFile.isBlank()) {
            Path path = Path.of(legacyKeyFile);
            if (Files.isRegularFile(path)) {
                PrivateKey configuredKey = readPrivateKey(path);
                if (!domain.isEmpty()) {
                    map.put(domain, new Key(domain, legacySelector, configuredKey));
                    log.info("DKIM enabled: selector={} domain={} source=configured", legacySelector, domain);
                }
            } else {
                log.warn("DKIM disabled: private key file {} not found (selector={} domain={})",
                        legacyKeyFile, legacySelector, domain);
            }
        }
        for (Key key : readDirectory(directory, legacySelector)) {
            Key existing = map.get(key.domain());
            if (existing == null) {
                map.put(key.domain(), key);
            } else if (existing.publicKeyBase64() == null
                    || !existing.publicKeyBase64().equals(key.publicKeyBase64())) {
                log.warn("DKIM: для домена {} заданы два разных ключа; действует явно заданный, "
                        + "ключ из {} не используется", key.domain(), directory);
            }
        }
        if (map.isEmpty()) {
            log.warn("DKIM disabled: ни один ключ не настроен (domain={} directory={})", domain, directory);
        }
        return new DkimKeyRing(map);
    }

    public Optional<Key> find(String domain) {
        return Optional.ofNullable(keys.get(normalize(domain)));
    }

    public List<Key> keys() {
        return keys.values().stream()
                .sorted(java.util.Comparator.comparing(Key::domain))
                .toList();
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public boolean contains(String domain) {
        return keys.containsKey(normalize(domain));
    }

    private static List<Key> readDirectory(String directory, String defaultSelector) {
        if (directory == null || directory.isBlank()) {
            return List.of();
        }
        Path root = Path.of(directory);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Key> loaded = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.pem")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String name = file.getFileName().toString();
                String candidate = normalize(name.substring(0, name.length() - ".pem".length()));
                if (!DOMAIN_FILE.matcher(candidate).matches()) {
                    log.warn("DKIM: файл {} пропущен — имя не является доменом", name);
                    continue;
                }
                String selector = defaultSelector;
                Path selectorFile = root.resolve(candidate + ".selector");
                if (Files.isRegularFile(selectorFile)) {
                    try {
                        String value = Files.readString(selectorFile).trim();
                        if (SELECTOR.matcher(value).matches()) {
                            selector = value;
                        } else {
                            log.warn("DKIM: селектор в {} некорректен, используется {}", selectorFile, defaultSelector);
                        }
                    } catch (IOException e) {
                        log.warn("DKIM: не удалось прочитать селектор {}, используется {}", selectorFile, defaultSelector);
                    }
                }
                try {
                    PrivateKey loadedKey = readPrivateKey(file);
                    loaded.add(new Key(candidate, selector, loadedKey));
                    log.info("DKIM enabled: selector={} domain={} source=directory", selector, candidate);
                } catch (RuntimeException e) {
                    // Один нечитаемый файл не должен отключать подпись остальных доменов,
                    // но и не должен остаться незамеченным.
                    log.error("DKIM: ключ {} не загружен: {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("DKIM: каталог ключей {} не прочитан: {}", directory, e.getMessage());
        }
        return loaded;
    }

    private static PrivateKey readPrivateKey(Path path) {
        try {
            String pem = Files.readString(path);
            String base64 = pem
                    .replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать DKIM ключ " + path, e);
        } catch (Exception e) {
            throw new IllegalStateException("DKIM ключ должен быть PKCS#8 PEM "
                    + "(openssl genpkey или openssl pkcs8 -topk8): " + e.getMessage(), e);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Старая конфигурация допускала список доменов через запятую
     * (`CORVEX_DKIM_DOMAIN=a,b`); действующим был первый. Такое поведение
     * сохраняется, чтобы обновление не сломало существующий `.env`.
     */
    private static String firstDomain(String value) {
        String normalized = normalize(value);
        int comma = normalized.indexOf(',');
        return comma < 0 ? normalized : normalized.substring(0, comma).trim();
    }
}
