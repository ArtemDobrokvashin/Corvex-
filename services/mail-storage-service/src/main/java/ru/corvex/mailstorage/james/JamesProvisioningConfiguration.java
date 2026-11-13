package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.security.InternalTokenGuard;

/**
 * OFF by default. Explicit configuration and a provisioned, isolated mTLS
 * administrative endpoint are required. Never enable based on unit tests.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "corvex.james.provision.enabled", havingValue = "true")
class JamesProvisioningConfiguration {
    @Bean(destroyMethod = "close")
    JamesWebAdminCreateClient jamesWebAdminCreateClient(ObjectMapper mapper,
            @Value("${corvex.james.provision.admin-origin}") String origin,
            @Value("${corvex.james.provision.admin-secret-file}") String secret,
            @Value("${corvex.james.provision.admin-keystore-file}") String keystore,
            @Value("${corvex.james.provision.admin-keystore-password-file}") String password,
            @Value("${corvex.james.provision.admin-trust-certificate-file}") String trust) throws Exception {
        String adminSecret = readSecret(Path.of(secret));
        char[] keyPassword = readSecret(Path.of(password)).toCharArray();
        try {
            return new JamesWebAdminCreateClient(URI.create(origin), adminSecret, mapper,
                    adminTls(Path.of(keystore), keyPassword, Path.of(trust)));
        } finally { Arrays.fill(keyPassword, '\0'); }
    }

    @Bean
    JamesProvisioningAccess jamesProvisioningAccess(AuthUserClient users, JdbcTemplate jdbc, DataSource dataSource,
            InternalTokenGuard internalTokens, ObjectMapper mapper, JamesWebAdminCreateClient admin,
            @Value("${corvex.james.provision.jmap-origin}") String jmapOrigin,
            @Value("${corvex.james.provision.jmap-private-key-file}") String privateKey,
            @Value("${corvex.james.provision.jmap-trust-certificate-file}") String jmapTrust,
            @Value("${corvex.admin-service-url:${CORVEX_ADMIN_SERVICE_URL:http://admin-service:8090}}") String adminOrigin)
            throws Exception {
        verifySchema(jdbc);
        var journal = new JamesProvisioningJournal(jdbc, new DataSourceTransactionManager(dataSource));
        var issuer = new JamesReadTokenIssuer(JamesReadConfiguration.loadPrivateKey(Path.of(privateKey)), Clock.systemUTC(), mapper);
        var jmap = new JamesJmapReadClient(URI.create(jmapOrigin), mapper,
                JamesReadConfiguration.loadDedicatedTrust(Path.of(jmapTrust)));
        var discovery = new JamesProvisioningDiscovery(journal, issuer, jmap);
        var permissions = new AdminMailPermissionClient(URI.create(adminOrigin), mapper, internalTokens);
        return new JamesProvisioningAccess(users, permissions, new JamesProvisioningCoordinator(journal, admin),
                discovery, Clock.systemUTC(), new SecureRandom());
    }

    static String readSecret(Path path) throws java.io.IOException {
        try {
            if (!path.isAbsolute() || !Files.isRegularFile(path) || Files.size(path) > 512) throw new java.io.IOException("Invalid provisioning secret");
            String value = Files.readString(path, StandardCharsets.US_ASCII).trim();
            if (!value.matches("[A-Za-z0-9_-]{32,256}")) throw new java.io.IOException("Invalid provisioning secret");
            return value;
        } catch (java.io.IOException e) { throw new java.io.IOException("Invalid provisioning secret"); }
    }

    static SSLContext adminTls(Path keyFile, char[] password, Path trustFile) throws Exception {
        if (!keyFile.isAbsolute() || !Files.isRegularFile(keyFile) || Files.size(keyFile) > 65536
                || !trustFile.isAbsolute() || !Files.isRegularFile(trustFile) || Files.size(trustFile) > 16384) {
            throw new IllegalArgumentException("Invalid provisioning TLS files");
        }
        var store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyFile)) { store.load(input, password); }
        int keys = 0;
        var aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (store.isKeyEntry(alias)) {
                keys++;
                if (!(store.getCertificate(alias) instanceof X509Certificate cert)) throw new IllegalArgumentException("Invalid client certificate");
                cert.checkValidity();
                var usages = cert.getExtendedKeyUsage();
                if (usages == null || !usages.contains("1.3.6.1.5.5.7.3.2")) throw new IllegalArgumentException("Client authentication certificate required");
            }
        }
        if (keys != 1) throw new IllegalArgumentException("Exactly one provisioning client key required");
        var keysFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keysFactory.init(store, password);
        var certFactory = CertificateFactory.getInstance("X.509");
        final java.util.Collection<? extends java.security.cert.Certificate> certificates;
        try (InputStream input = Files.newInputStream(trustFile)) { certificates = certFactory.generateCertificates(input); }
        if (certificates.size() != 1 || !(certificates.iterator().next() instanceof X509Certificate cert)) {
            throw new IllegalArgumentException("Expected one provisioning trust certificate");
        }
        cert.checkValidity();
        var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("provision-server", cert);
        var trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustFactory.init(trustStore);
        var tls = SSLContext.getInstance("TLS");
        tls.init(keysFactory.getKeyManagers(), trustFactory.getTrustManagers(), null);
        return tls;
    }

    static void verifySchema(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc);
        jdbc.queryForList("SELECT owner_id, enabled, james_login, account_id FROM corvex_james_mailbox_binding WHERE false");
        jdbc.queryForList("SELECT operation_id, owner_id, actor_id, james_login, phase FROM corvex_james_provisioning WHERE false");
        jdbc.queryForList("SELECT operation_id, actor_id, event FROM corvex_james_provisioning_event WHERE false");
        requireConstraint(jdbc, "corvex_james_provisioning", "PRIMARY KEY (operation_id)");
        requireConstraint(jdbc, "corvex_james_provisioning", "UNIQUE (owner_id)");
        requireConstraint(jdbc, "corvex_james_provisioning", "UNIQUE (james_login)");
        requireConstraint(jdbc, "corvex_james_mailbox_binding", "PRIMARY KEY (owner_id)");
        requireConstraint(jdbc, "corvex_james_mailbox_binding", "UNIQUE (account_id)");
        Integer indexes = jdbc.queryForObject("SELECT count(*) FROM pg_index i WHERE i.indrelid = to_regclass(?) "
                + "AND i.indisunique AND i.indisvalid AND i.indisready AND i.indpred IS NULL AND pg_get_indexdef(i.indexrelid) LIKE '%(lower(james_login))%'",
                Integer.class, "corvex_james_mailbox_binding");
        if (indexes == null || indexes < 1) throw new IllegalStateException("Missing James login uniqueness constraint");
    }

    private static void requireConstraint(JdbcTemplate jdbc, String table, String definition) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass(?) "
                + "AND pg_get_constraintdef(oid) = ?", Integer.class, table, definition);
        if (count == null || count != 1) throw new IllegalStateException("Missing James provisioning uniqueness constraint");
    }
}
