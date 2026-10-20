package ru.corvex.mailsmtp.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
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
 * <p>Подписываются только письма, чей From-домен равен настроенному d=. Приватный ключ —
 * PKCS#8 PEM (`BEGIN PRIVATE KEY`, так его генерирует first-run через openssl genpkey).
 * Если ключ не настроен, письмо уходит без подписи и воркер честно пишет warning:
 * доставляемость тогда держится на SPF/PTR, а DKIM включается одним файлом и DNS-записью.
 */
@Component
public class DkimSigner {

    private static final Logger log = LoggerFactory.getLogger(DkimSigner.class);
    private static final List<String> SIGN_HEADERS =
            List.of("from", "to", "cc", "subject", "date", "message-id", "mime-version", "content-type");

    private final String domain;
    private final String selector;
    private final PrivateKey privateKey;

    @Autowired
    public DkimSigner(@Value("${corvex.dkim.domain:${CORVEX_DKIM_DOMAIN:${CORVEX_MAIL_DOMAIN:company.local}}}") String domain,
                      @Value("${corvex.dkim.selector:${CORVEX_DKIM_SELECTOR:corvex1}}") String selector,
                      @Value("${corvex.dkim.private-key-file:${CORVEX_DKIM_PRIVATE_KEY_FILE:/run/secrets/dkim_private.pem}}") String keyFile) {
        this.domain = firstDomain(domain);
        this.selector = selector;
        this.privateKey = loadKey(keyFile);
        if (privateKey == null) {
            log.warn("DKIM disabled: private key file {} not found (selector={} domain={})", keyFile, selector, this.domain);
        } else {
            log.info("DKIM enabled: selector={} domain={}", selector, this.domain);
        }
    }

    DkimSigner(String domain, String selector, PrivateKey privateKey) {
        this.domain = domain;
        this.selector = selector;
        this.privateKey = privateKey;
    }

    public boolean isEnabled() {
        return privateKey != null;
    }

    public byte[] signIfConfigured(byte[] raw, String envelopeFrom) {
        if (!isEnabled()) {
            return raw;
        }
        ParsedRaw parsed = parse(raw);
        String fromDomain = fromDomain(parsed.headers(), envelopeFrom);
        if (!domain.equalsIgnoreCase(fromDomain)) {
            log.warn("DKIM пропущен: From-домен {} не равен настроенному d={}", fromDomain, domain);
            return raw;
        }
        if (parsed.headers().stream().anyMatch(h -> h.name().equals("dkim-signature")
                && h.value().toLowerCase(Locale.ROOT).contains("d=" + domain.toLowerCase(Locale.ROOT)))) {
            return raw;
        }
        return sign(parsed);
    }

    byte[] sign(byte[] raw) {
        return sign(parse(raw));
    }

    private byte[] sign(ParsedRaw parsed) {
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
            String dkimValue = "v=1; a=rsa-sha256; c=relaxed/relaxed; d=" + domain + "; s=" + selector
                    + "; t=" + Instant.now().getEpochSecond() + "; bh=" + bodyHash
                    + "; h=" + String.join(":", signedNames) + "; b=";
            data.append(canonicalHeader("dkim-signature", dkimValue));

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
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

    private static String firstDomain(String csvOrDomain) {
        return csvOrDomain.split(",")[0].trim().toLowerCase(Locale.ROOT);
    }

    private PrivateKey loadKey(String keyFile) {
        try {
            Path path = Path.of(keyFile);
            if (!Files.isRegularFile(path)) {
                return null;
            }
            String pem = Files.readString(path);
            String base64 = pem
                    .replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать DKIM ключ " + keyFile, e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "DKIM ключ должен быть PKCS#8 PEM (openssl genpkey или openssl pkcs8 -topk8): " + e.getMessage(), e);
        }
    }
}
