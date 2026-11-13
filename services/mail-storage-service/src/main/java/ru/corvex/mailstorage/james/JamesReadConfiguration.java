package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.security.InternalTokenGuard;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;

/** Disabled by default; never enable before provisioning and end-to-end security review. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "corvex.james.read.enabled", havingValue = "true")
class JamesReadConfiguration {
    @Bean
    JamesOwnMailboxAccess jamesOwnMailboxAccess(
            AuthUserClient users, JdbcTemplate jdbc, InternalTokenGuard internalTokens, ObjectMapper mapper,
            @Value("${corvex.james.read.origin}") String jamesOrigin,
            @Value("${corvex.james.read.private-key-file}") String keyFile,
            @Value("${corvex.admin-service-url:${CORVEX_ADMIN_SERVICE_URL:http://admin-service:8090}}") String adminOrigin)
            throws Exception {
        // Explicit configuration only, no demo key, no fallback to the auth JWT key.
        RSAPrivateKey key = loadPrivateKey(Path.of(keyFile));
        var tokenIssuer = new JamesReadTokenIssuer(key, Clock.systemUTC(), mapper);
        var jamesClient = new JamesJmapReadClient(URI.create(jamesOrigin), mapper);
        var currentPermissions = new AdminMailPermissionClient(URI.create(adminOrigin), mapper, internalTokens);
        return new JamesOwnMailboxAccess(users, new JdbcJamesMailboxRegistry(jdbc), currentPermissions,
                new JamesOwnMailboxReader(tokenIssuer, jamesClient, mapper), new JamesFolderMapper(), Clock.systemUTC());
    }

    static RSAPrivateKey loadPrivateKey(Path path) throws Exception {
        if (!path.isAbsolute() || !Files.isRegularFile(path) || Files.size(path) > 16384) {
            throw new IllegalArgumentException("Invalid dedicated James private key file");
        }
        String pem = Files.readString(path, StandardCharsets.US_ASCII).trim();
        String begin = "-----BEGIN PRIVATE KEY-----";
        String end = "-----END PRIVATE KEY-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) throw new IllegalArgumentException("Expected PKCS#8 PEM key");
        String encoded = pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(encoded);
        var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        if (!(key instanceof RSAPrivateKey rsa) || rsa.getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("James private key must be RSA 2048 or stronger");
        }
        return rsa;
    }
}
