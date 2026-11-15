package ru.corvex.mailsmtp.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DKIM rsa-sha256 с canonicalization relaxed/relaxed (RFC 6376).
 *
 * <p>Ключ выбирается по домену From из набора {@link DkimKeyRing}: один сервер
 * подписывает столько доменов, для скольких загружены ключи. Приватный ключ —
 * PKCS#8 PEM (`BEGIN PRIVATE KEY`, так его генерирует first-run через openssl genpkey).
 * Если ключа для домена нет, письмо уходит без подписи и воркер честно пишет
 * warning: доставляемость тогда держится на SPF/PTR.
 */
@Component
public class DkimSigner {

    private static final Logger log = LoggerFactory.getLogger(DkimSigner.class);
    private static final List<String> SIGN_HEADERS =
            List.of("from", "to", "cc", "subject", "date", "message-id", "mime-version", "content-type");

    private final DkimKeyRing ring;

    @Autowired
    public DkimSigner(@Value("${corvex.dkim.domain:${CORVEX_DKIM_DOMAIN:${CORVEX_MAIL_DOMAIN:company.local}}}") String domain,
                      @Value("${corvex.dkim.selector:${CORVEX_DKIM_SELECTOR:corvex1}}") String selector,
                      @Value("${corvex.dkim.private-key-file:${CORVEX_DKIM_PRIVATE_KEY_FILE:/run/secrets/dkim_private.pem}}") String keyFile,
                      @Value("${corvex.dkim.keys-directory:${CORVEX_DKIM_KEYS_DIR:/run/secrets/dkim}}") String keysDirectory) {
        this.ring = DkimKeyRing.load(domain, selector, keyFile, keysDirectory);
    }

    /** Только явный файл ключа, без каталога: так проверяется загрузка и ошибки PEM. */
    DkimSigner(String domain, String selector, String keyFile) {
        this.ring = DkimKeyRing.load(domain, selector, keyFile, null);
    }

    DkimSigner(String domain, String selector, PrivateKey privateKey) {
        this.ring = DkimKeyRing.single(domain, selector, privateKey);
    }

    public DkimSigner(DkimKeyRing ring) {
        this.ring = ring;
    }

    public boolean isEnabled() {
        return !ring.isEmpty();
    }

    /** Набор ключей по доменам: один сервер может подписывать несколько доменов. */
    public DkimKeyRing keyRing() {
        return ring;
    }

    /** Домен единственного ключа; null, если ключей несколько или нет ни одного. */
    public String domain() {
        return ring.keys().size() == 1 ? ring.keys().get(0).domain() : null;
    }

    public String selector() {
        return ring.keys().size() == 1 ? ring.keys().get(0).selector() : null;
    }

    /** Публичная часть ключа единственного домена; null при нескольких ключах или их отсутствии. */
    public String publicKeyBase64() {
        return ring.keys().size() == 1 ? ring.keys().get(0).publicKeyBase64() : null;
    }

    /**
     * Подписывает письмо ключом домена From. Нет ключа для этого домена —
     * письмо уходит без подписи с предупреждением: подписывать чужим ключом
     * нельзя, а отказывать в отправке из-за ненастроенного DKIM — тоже.
     */
    public byte[] signIfConfigured(byte[] raw, String envelopeFrom) {
        if (!isEnabled()) {
            return raw;
        }
        ParsedRaw parsed = parse(raw);
        String fromDomain = fromDomain(parsed.headers(), envelopeFrom);
        DkimKeyRing.Key key = ring.find(fromDomain).orElse(null);
        if (key == null) {
            log.warn("DKIM пропущен: для From-домена {} нет ключа", fromDomain);
            return raw;
        }
        if (parsed.headers().stream().anyMatch(h -> h.name().equals("dkim-signature")
                && h.value().toLowerCase(Locale.ROOT).contains("d=" + key.domain()))) {
            return raw;
        }
        return sign(parsed, key);
    }

    byte[] sign(byte[] raw) {
        return ring.keys().isEmpty() ? raw : sign(parse(raw), ring.keys().get(0));
    }

    private byte[] sign(ParsedRaw parsed, DkimKeyRing.Key key) {
        try {
            String bodyHash = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(canonicalBody(parsed.body())));

            Map<String, Header> lastByName = new LinkedHashMap<>();
            for (Header h : parsed.headers()) {
                lastByName.put(h.name(), h); // нижний экземпляр заголовка — как требует RFC 6376 §5.4
            }
            List<String> signedNames = new ArrayList<>();
            StringBuilder data = new StringBuilder();
            for (String name : SIGN_HEADERS) {
                Header h = lastByName.get(name);
                if (h != null) {
                    signedNames.add(name);
                    data.append(canonicalHeader(name, h.value())).append("\r\n");
                }
            }
            String dkimValue = "v=1; a=rsa-sha256; c=relaxed/relaxed; d=" + key.domain() + "; s=" + key.selector()
                    + "; t=" + Instant.now().getEpochSecond() + "; bh=" + bodyHash
                    + "; h=" + String.join(":", signedNames) + "; b=";
            data.append(canonicalHeader("dkim-signature", dkimValue));

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key.privateKey());
            signature.update(data.toString().getBytes(StandardCharsets.ISO_8859_1));
            String b = Base64.getEncoder().encodeToString(signature.sign());

            String header = "DKIM-Signature: " + dkimValue + fold(b) + "\r\n";
            byte[] headerBytes = header.getBytes(StandardCharsets.ISO_8859_1);
            byte[] out = new byte[headerBytes.length + parsed.original().length];
            System.arraycopy(headerBytes, 0, out, 0, headerBytes.length);
            System.arraycopy(parsed.original(), 0, out, headerBytes.length, parsed.original().length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось подписать DKIM", e);
        }
    }

    private static String fold(String b64) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b64.length(); i += 72) {
            if (i > 0) {
                sb.append("\r\n\t");
            }
            sb.append(b64, i, Math.min(b64.length(), i + 72));
        }
        return sb.toString();
    }

    static String canonicalHeader(String name, String rawValue) {
        String value = rawValue
                .replaceAll("(\r\n|\r|\n)(?=[ \t])", "")
                .replaceAll("[ \t]+", " ")
                .trim();
        return name.trim().toLowerCase(Locale.ROOT) + ":" + value;
    }

    static byte[] canonicalBody(byte[] body) {
        String s = new String(body, StandardCharsets.ISO_8859_1)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        String[] lines = s.split("\n", -1);
        List<String> canonical = new ArrayList<>();
        for (String line : lines) {
            canonical.add(line.replaceAll("[ \t]+", " ").replaceAll(" +$", ""));
        }
        while (!canonical.isEmpty() && canonical.get(canonical.size() - 1).isEmpty()) {
            canonical.remove(canonical.size() - 1);
        }
        if (canonical.isEmpty()) {
            return new byte[0];
        }
        return (String.join("\r\n", canonical) + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private record Header(String name, String value) {
    }

    private record ParsedRaw(byte[] original, List<Header> headers, byte[] body) {
    }

    private ParsedRaw parse(byte[] raw) {
        String all = new String(raw, StandardCharsets.ISO_8859_1);
        int sep = all.indexOf("\r\n\r\n");
        int sepLen = 4;
        if (sep < 0) {
            sep = all.indexOf("\n\n");
            sepLen = 2;
        }
        String head = sep >= 0 ? all.substring(0, sep) : all;
        byte[] body = sep >= 0
                ? all.substring(sep + sepLen).getBytes(StandardCharsets.ISO_8859_1)
                : new byte[0];

        List<Header> headers = new ArrayList<>();
        StringBuilder current = null;
        for (String line : head.split("\r\n|\n", -1)) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && current != null) {
                current.append("\r\n").append(line);
            } else {
                if (current != null) {
                    headers.add(toHeader(current.toString()));
                }
                current = new StringBuilder(line);
            }
        }
        if (current != null && !current.toString().isBlank()) {
            headers.add(toHeader(current.toString()));
        }
        return new ParsedRaw(raw, headers, body);
    }

    private Header toHeader(String rawHeader) {
        int colon = rawHeader.indexOf(':');
        if (colon < 0) {
            return new Header("", rawHeader);
        }
        return new Header(rawHeader.substring(0, colon).trim().toLowerCase(Locale.ROOT), rawHeader.substring(colon + 1));
    }

    private String fromDomain(List<Header> headers, String envelopeFrom) {
        for (int i = headers.size() - 1; i >= 0; i--) {
            if (headers.get(i).name().equals("from")) {
                String domainPart = domainOf(headers.get(i).value());
                if (!domainPart.isBlank()) {
                    return domainPart;
                }
            }
        }
        return domainOf(envelopeFrom);
    }

    private static String domainOf(String addressLike) {
        if (addressLike == null) {
            return "";
        }
        String s = addressLike;
        int lt = s.indexOf('<');
        int gt = s.indexOf('>', lt + 1);
        if (lt >= 0 && gt > lt) {
            s = s.substring(lt + 1, gt);
        }
        int at = s.lastIndexOf('@');
        if (at < 0) {
            return "";
        }
        return s.substring(at + 1).replaceAll("[\\s>].*$", "").trim().toLowerCase(Locale.ROOT);
    }

}
