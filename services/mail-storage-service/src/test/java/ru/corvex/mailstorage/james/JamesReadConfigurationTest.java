package ru.corvex.mailstorage.james;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class JamesReadConfigurationTest {
    @TempDir Path tmp;

    @Test void readFeatureIsDisabledByDefaultAndFailsClosedWithoutDependencies() {
        var runner = new ApplicationContextRunner().withUserConfiguration(JamesReadConfiguration.class, JamesPreviewController.class);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(JamesOwnMailboxAccess.class);
            assertThat(context).doesNotHaveBean(JamesPreviewController.class);
        });
        runner.withPropertyValues("corvex.james.read.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void registryReadinessFailsClosedWhenDatabaseIsUnavailable() {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> JamesReadConfiguration.verifyRegistryReadable(jdbc));
    }

    @Test void dedicatedTrustRejectsInvalidOrMultipleCertificates() throws Exception {
        Path certificate = Path.of(getClass().getResource("/james/test-trust-cert.crt").toURI()).toAbsolutePath();
        assertNotNull(JamesReadConfiguration.loadDedicatedTrust(certificate));
        var original = Files.readString(certificate);
        Path malformed = tmp.resolve("bad-cert.pem").toAbsolutePath();
        Files.writeString(malformed, "not a certificate");
        assertThrows(Exception.class, () -> JamesReadConfiguration.loadDedicatedTrust(malformed));
        Path doubled = tmp.resolve("two-certs.pem").toAbsolutePath();
        Files.writeString(doubled, original + "\n" + original);
        assertThrows(Exception.class, () -> JamesReadConfiguration.loadDedicatedTrust(doubled));
        assertThrows(Exception.class, () -> JamesReadConfiguration.loadDedicatedTrust(Path.of("relative.pem")));
    }

    @Test void loadsDedicatedPkcs8RsaKeyAndRejectsInvalidOrWeakKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var privateKey = generator.generateKeyPair().getPrivate();
        Path valid = tmp.resolve("test-only.pem").toAbsolutePath();
        String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(privateKey.getEncoded());
        Files.writeString(valid, "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n");
        assertArrayEquals(privateKey.getEncoded(), JamesReadConfiguration.loadPrivateKey(valid).getEncoded());
        Path malformed = tmp.resolve("bad.pem").toAbsolutePath();
        Files.writeString(malformed, "not a key");
        assertThrows(Exception.class, () -> JamesReadConfiguration.loadPrivateKey(malformed));
        assertThrows(Exception.class, () -> JamesReadConfiguration.loadPrivateKey(Path.of("relative.pem")));
        generator.initialize(1024);
        var weak = generator.generateKeyPair().getPrivate();
        Path weakFile = tmp.resolve("weak.pem").toAbsolutePath();
        Files.writeString(weakFile, "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(weak.getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        assertThrows(Exception.class, () -> JamesReadConfiguration.loadPrivateKey(weakFile));
    }
}
