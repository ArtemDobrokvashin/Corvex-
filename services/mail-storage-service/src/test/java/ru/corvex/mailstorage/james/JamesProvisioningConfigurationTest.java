package ru.corvex.mailstorage.james;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Configuration guards only; no live keys, PostgreSQL or WebAdmin. */
class JamesProvisioningConfigurationTest {
    @TempDir Path temporary;

    @Test void offByDefaultHasNoProvisioningBeans() {
        new ApplicationContextRunner().withUserConfiguration(JamesProvisioningConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("jamesProvisioningAccess"));
            assertFalse(context.containsBean("jamesWebAdminCreateClient"));
        });
    }

    @Test void explicitEnableWithoutDependenciesFailsClosed() {
        new ApplicationContextRunner().withUserConfiguration(JamesProvisioningConfiguration.class)
                .withPropertyValues("corvex.james.provision.enabled=true").run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test void dedicatedSecretMustBeAnAbsoluteBoundedValidFile() throws Exception {
        var file = temporary.resolve("secret.txt").toAbsolutePath();
        Files.writeString(file, "A".repeat(40), StandardCharsets.US_ASCII);
        assertEquals("A".repeat(40), JamesProvisioningConfiguration.readSecret(file));
        Files.writeString(file, "unsafe", StandardCharsets.US_ASCII);
        assertThrows(java.io.IOException.class, () -> JamesProvisioningConfiguration.readSecret(file));
        assertThrows(java.io.IOException.class, () -> JamesProvisioningConfiguration.readSecret(Path.of("relative")));
    }

    @Test void schemaGuardFailsClosedWhenConstraintMissing() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1, 1, 0);
        assertThrows(IllegalStateException.class, () -> JamesProvisioningConfiguration.verifySchema(jdbc));
    }

    @Test void tlsRejectsMissingFiles() {
        assertThrows(IllegalArgumentException.class, () -> JamesProvisioningConfiguration.adminTls(
                Path.of("relative"), "X".repeat(32).toCharArray(), temporary.resolve("missing")));
    }
}
