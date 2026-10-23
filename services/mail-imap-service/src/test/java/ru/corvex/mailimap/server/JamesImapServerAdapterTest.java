package ru.corvex.mailimap.server;

import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.james.imap.api.process.ImapProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * SmartLifecycle-интеграция Джеймса: Spring должен реально вызывать start/stop адаптера,
 * а выключенный флагом IMAP не должен пытаться открыть порты или ронять контекст.
 */
class JamesImapServerAdapterTest {

    @TempDir
    Path tempDir;

    private JamesImapServerAdapter adapter(boolean enabled, boolean plainEnabled, boolean tlsEnabled) {
        return adapter(enabled, "127.0.0.1", plainEnabled, false, tlsEnabled, false, true,
                "", "", "PKCS12", "", "", "localhost", "", 1024,
                65536, 65536, 1800, 120, 0, 0);
    }

    private JamesImapServerAdapter adapter(
            boolean enabled,
            String bindAddress,
            boolean plainEnabled,
            boolean starttlsEnabled,
            boolean tlsEnabled,
            boolean requireTlsForAuth,
            boolean plainAuthEnabled,
            String keystorePath,
            String keystorePassword,
            String keystoreType,
            String certificatePath,
            String privateKeyPath,
            String helloName,
            String disabledCaps,
            long literalSizeLimitBytes,
            long inMemorySizeLimitBytes,
            int maxLineLength,
            int connectionTimeoutSeconds,
            int idleHeartbeatSeconds,
            int maxConnections,
            int maxConnectionsPerIp) {
        return new JamesImapServerAdapter(
                mock(ImapProcessor.class),
                NoopMetricFactory.INSTANCE,
                enabled,
                bindAddress,
                plainEnabled,
                0,
                starttlsEnabled,
                tlsEnabled,
                0,
                requireTlsForAuth,
                plainAuthEnabled,
                keystorePath,
                keystorePassword,
                keystoreType,
                certificatePath,
                privateKeyPath,
                helloName,
                disabledCaps,
                literalSizeLimitBytes,
                inMemorySizeLimitBytes,
                maxLineLength,
                connectionTimeoutSeconds,
                idleHeartbeatSeconds,
                maxConnections,
                maxConnectionsPerIp);
    }

    @Test
    void disabledAdapterStartsAndStopsAsNoopLifecycleBean() {
        JamesImapServerAdapter adapter = adapter(false, true, true);

        assertDoesNotThrow(adapter::start);
        assertFalse(adapter.isRunning(), "CORVEX_IMAP_ENABLED=false не должен открывать слушатели");
        assertDoesNotThrow(() -> adapter.notifyNewMail("ivanov@corp.local", "INBOX"));
        assertDoesNotThrow(() -> adapter.stop());
        assertFalse(adapter.isRunning());
    }

    @Test
    void enabledAdapterWithNoListenersFailsFastAndStaysStopped() {
        JamesImapServerAdapter adapter = adapter(true, false, false);

        assertThrows(IllegalStateException.class, adapter::start,
                "включённый IMAP без единого слушателя — ошибка конфигурации, а не тихий мёртвый порт");
        assertFalse(adapter.isRunning());
    }

    @Test
    void tlsOnlyWithoutMaterialAlsoFailsInsteadOfReportingRunning() {
        JamesImapServerAdapter adapter = adapter(true, false, true);

        IllegalStateException error = assertThrows(IllegalStateException.class, adapter::start);
        assertTrue(error.getMessage().contains("не стартует ни один слушатель"));
        assertFalse(adapter.isRunning());
        assertDoesNotThrow(() -> adapter.stop());
    }

    @Test
    void plainServerStartsOnceDegradesMissingStartTlsAndStopsCleanly() {
        JamesImapServerAdapter adapter = adapter(
                true, "127.0.0.1", true, true, false, true, true,
                "", "", "PKCS12", "", "", "localhost", "", 1024,
                65536, 65536, 1800, 120, 0, 0);
        try {
            adapter.start();
            assertTrue(adapter.isRunning());
            adapter.start();
            assertTrue(adapter.isRunning(), "повторный SmartLifecycle start не должен плодить слушатели");
        } finally {
            adapter.stop();
        }
        assertFalse(adapter.isRunning());
        assertDoesNotThrow(() -> adapter.stop());
    }

    @Test
    void invalidBindAddressDuringServerInitBecomesIllegalStateAndCanStop() {
        JamesImapServerAdapter adapter = adapter(
                true, "256.256.256.256", true, false, false, false, true,
                "", "", "PKCS12", "", "", "localhost", "", 1024,
                65536, 65536, 1800, 120, 0, 0);

        IllegalStateException error = assertThrows(IllegalStateException.class, adapter::start);
        assertTrue(error.getMessage().contains("Не удалось стартовать Apache James IMAP"));
        assertFalse(adapter.isRunning());
        assertDoesNotThrow(() -> adapter.stop());
    }

    @Test
    void plainConfigurationClampsUnsafeLimitsEscapesXmlAndWritesAuthSettings() throws Exception {
        JamesImapServerAdapter adapter = adapter(
                true, "127.0.0.1", true, false, false, true, false,
                "", "", "PKCS12", "", "",
                "Mail & <Server> \"One\" 'x'", " CONDSTORE QRESYNC ", 0,
                1, 1, 1, 1, -5, -7);

        HierarchicalConfiguration<ImmutableNode> config = configuration(adapter, "test-imap", 1143, false, false);
        assertEquals("test-imap", config.getString("jmxName"));
        assertEquals("127.0.0.1:1143", config.getString("bind"));
        assertEquals(200, config.getInt("connectionBacklog"));
        assertEquals(0, config.getInt("connectionLimit"));
        assertEquals(0, config.getInt("connectionLimitPerIP"));
        assertEquals(1800, config.getInt("timeout"));
        assertEquals("Mail & <Server> \"One\" 'x'", config.getString("helloName"));
        assertTrue(config.getBoolean("enableIdle"));
        assertEquals(10, config.getInt("idleTimeInterval"));
        assertEquals("SECONDS", config.getString("idleTimeIntervalUnit"));
        assertEquals(65536, config.getInt("inMemorySizeLimit"));
        assertFalse(config.containsKey("literalSizeLimit"));
        assertEquals(8192, config.getInt("maxLineLength"));
        assertEquals("CONDSTORE QRESYNC", config.getString("disabledCaps"));
        assertFalse(config.containsKey("tls"));
        assertFalse(config.getBoolean("gracefulShutdown"));

        HierarchicalConfiguration<ImmutableNode> auth = config.configurationAt("auth");
        assertTrue(auth.getBoolean("requireSSL"));
        assertFalse(auth.getBoolean("plainAuthEnabled"));
    }

    @Test
    void tlsConfigurationUsesKeystoreOrCertificatePairAndOptionalSecret() throws Exception {
        JamesImapServerAdapter keystoreAdapter = adapter(
                true, "0.0.0.0", true, true, true, false, true,
                " /certs/imap.p12 ", "changeit", " ", "ignored.crt", "ignored.key",
                "localhost", null, 12345, 1048576, 32768, 3600, 60, 100, 10);
        HierarchicalConfiguration<ImmutableNode> keystoreConfig =
                configuration(keystoreAdapter, "keystore-imap", 1993, true, false);
        assertEquals(12345, keystoreConfig.getLong("literalSizeLimit"));
        assertEquals("", keystoreConfig.getString("disabledCaps"));
        HierarchicalConfiguration<ImmutableNode> keystoreTls = keystoreConfig.configurationAt("tls");
        assertEquals("/certs/imap.p12", keystoreTls.getString("keystore"));
        assertEquals("PKCS12", keystoreTls.getString("keystoreType"));
        assertEquals("changeit", keystoreTls.getString("secret"));
        assertFalse(keystoreTls.containsKey("privateKey"));

        JamesImapServerAdapter certificateAdapter = adapter(
                true, "0.0.0.0", false, false, true, false, true,
                "", " ", "JKS", " /certs/fullchain.pem ", " /certs/privkey.pem ",
                "localhost", "", 1, 65536, 8192, 1800, 10, 0, 0);
        HierarchicalConfiguration<ImmutableNode> certificateTls = configuration(
                certificateAdapter, "certificate-imap", 1993, false, true).configurationAt("tls");
        assertEquals("/certs/privkey.pem", certificateTls.getString("privateKey"));
        assertEquals("/certs/fullchain.pem", certificateTls.getString("certificates"));
        assertFalse(certificateTls.containsKey("keystore"));
        assertFalse(certificateTls.containsKey("secret"));
    }

    @Test
    void tlsMaterialDetectionHandlesFilesClasspathAndIncompletePairs() throws Exception {
        Path keystore = Files.writeString(tempDir.resolve("imap.p12"), "dummy");
        Path certificate = Files.writeString(tempDir.resolve("cert.pem"), "dummy");
        Path privateKey = Files.writeString(tempDir.resolve("key.pem"), "dummy");

        assertTrue(hasTlsMaterial(adapterWithMaterial(keystore.toString(), "", "")));
        assertFalse(hasTlsMaterial(adapterWithMaterial(tempDir.resolve("missing.p12").toString(),
                certificate.toString(), privateKey.toString())),
                "заданный, но отсутствующий keystore не маскируется парой cert/key");
        assertTrue(hasTlsMaterial(adapterWithMaterial("classpath:imap.p12", "", "")));
        assertTrue(hasTlsMaterial(adapterWithMaterial("", certificate.toString(), privateKey.toString())));
        assertFalse(hasTlsMaterial(adapterWithMaterial("", certificate.toString(), "")));
        assertFalse(hasTlsMaterial(adapterWithMaterial("", certificate.toString(),
                tempDir.resolve("missing.key").toString())));
        assertFalse(hasTlsMaterial(adapterWithMaterial(" ", " ", " ")));

        assertTrue(materialExists(certificate.toString()));
        assertFalse(materialExists(tempDir.toString()));
        assertTrue(materialExists("classpath:cert.pem"));
        assertFalse(materialExists("file://definitely-missing-corvex-imap.pem"));
        assertFalse(materialExists("file:///definitely-missing-corvex-imap.pem"));
    }

    @Test
    void xmlHelpersEscapeValuesAndNullsDeterministically() throws Exception {
        Method escape = JamesImapServerAdapter.class.getDeclaredMethod("escapeXml", String.class);
        escape.setAccessible(true);
        assertEquals("a&amp;&lt;&gt;&quot;&apos;", escape.invoke(null, "a&<>\"'"));

        Method element = JamesImapServerAdapter.class.getDeclaredMethod(
                "element", StringBuilder.class, String.class, String.class);
        element.setAccessible(true);
        StringBuilder xml = new StringBuilder();
        element.invoke(null, xml, "empty", null);
        element.invoke(null, xml, "value", "a&b");
        assertEquals("<empty></empty><value>a&amp;b</value>", xml.toString());

        Method blank = JamesImapServerAdapter.class.getDeclaredMethod("isBlank", String.class);
        blank.setAccessible(true);
        assertTrue((Boolean) blank.invoke(null, new Object[]{null}));
        assertTrue((Boolean) blank.invoke(null, " "));
        assertFalse((Boolean) blank.invoke(null, "x"));
    }

    private JamesImapServerAdapter adapterWithMaterial(String keystore, String certificate, String privateKey) {
        return adapter(true, "127.0.0.1", true, true, true, false, true,
                keystore, "", "PKCS12", certificate, privateKey, "localhost", "", 1024,
                65536, 65536, 1800, 120, 0, 0);
    }

    private static HierarchicalConfiguration<ImmutableNode> configuration(
            JamesImapServerAdapter adapter, String jmxName, int port, boolean startTls, boolean socketTls)
            throws Exception {
        Method method = JamesImapServerAdapter.class.getDeclaredMethod(
                "serverConfiguration", String.class, int.class, boolean.class, boolean.class);
        method.setAccessible(true);
        return (HierarchicalConfiguration<ImmutableNode>) method.invoke(adapter, jmxName, port, startTls, socketTls);
    }

    private static boolean hasTlsMaterial(JamesImapServerAdapter adapter) throws Exception {
        Method method = JamesImapServerAdapter.class.getDeclaredMethod("hasTlsMaterial");
        method.setAccessible(true);
        return (Boolean) method.invoke(adapter);
    }

    private static boolean materialExists(String path) throws Exception {
        Method method = JamesImapServerAdapter.class.getDeclaredMethod("materialExists", String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, path);
    }
}
