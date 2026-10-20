package ru.corvex.mailimap.server;

import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.XMLConfiguration;
import org.apache.commons.configuration2.io.FileHandler;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.james.imap.api.process.ImapProcessor;
import org.apache.james.imap.encode.main.DefaultImapEncoderFactory;
import org.apache.james.imap.main.DefaultImapDecoderFactory;
import org.apache.james.imapserver.netty.IMAPServer;
import org.apache.james.imapserver.netty.ImapMetrics;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Apache James IMAP4rev1 поверх CorvexMailboxManager (storage protocol API).
 * Два слушателя одним ImapProcessor-ом: plain+STARTTLS и implicit TLS.
 * Протокольный TCP/RFC-код не пишем сами никогда — только конфигурация Джеймса.
 */
@Component
public class JamesImapServerAdapter implements ImapServerPort, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JamesImapServerAdapter.class);

    private final ImapProcessor processor;
    private final MetricFactory metricFactory;
    private final boolean enabled;
    private final String bindAddress;
    private final boolean plainEnabled;
    private final int plainPort;
    private final boolean starttlsEnabled;
    private final boolean tlsEnabled;
    private final int tlsPort;
    private final boolean requireTlsForAuth;
    private final boolean plainAuthEnabled;
    private final String keystorePath;
    private final String keystorePassword;
    private final String keystoreType;
    private final String certificatePath;
    private final String privateKeyPath;
    private final String helloName;
    private final String disabledCaps;
    private final long literalSizeLimitBytes;
    private final long inMemorySizeLimitBytes;
    private final int maxLineLength;
    private final int connectionTimeoutSeconds;
    private final int idleHeartbeatSeconds;
    private final int maxConnections;
    private final int maxConnectionsPerIp;

    private final List<IMAPServer> servers = new ArrayList<>();
    private boolean running;

    public JamesImapServerAdapter(
            ImapProcessor processor,
            MetricFactory metricFactory,
            @Value("${corvex.imap.enabled:false}") boolean enabled,
            @Value("${corvex.imap.bind-address:0.0.0.0}") String bindAddress,
            @Value("${corvex.imap.plain-enabled:true}") boolean plainEnabled,
            @Value("${corvex.imap.plain-port:1143}") int plainPort,
            @Value("${corvex.imap.starttls-enabled:true}") boolean starttlsEnabled,
            @Value("${corvex.imap.tls-enabled:true}") boolean tlsEnabled,
            @Value("${corvex.imap.tls-port:1993}") int tlsPort,
            @Value("${corvex.imap.require-tls-for-auth:false}") boolean requireTlsForAuth,
            @Value("${corvex.imap.plain-auth-enabled:true}") boolean plainAuthEnabled,
            @Value("${corvex.imap.keystore-path:}") String keystorePath,
            @Value("${corvex.imap.keystore-password:}") String keystorePassword,
            @Value("${corvex.imap.keystore-type:PKCS12}") String keystoreType,
            @Value("${corvex.imap.certificate-path:}") String certificatePath,
            @Value("${corvex.imap.private-key-path:}") String privateKeyPath,
            @Value("${corvex.imap.hello-name:localhost}") String helloName,
            @Value("${corvex.imap.disabled-caps:}") String disabledCaps,
            @Value("${corvex.imap.literal-size-limit-bytes:26214400}") long literalSizeLimitBytes,
            @Value("${corvex.imap.in-memory-size-limit-bytes:10485760}") long inMemorySizeLimitBytes,
            @Value("${corvex.imap.max-line-length:65536}") int maxLineLength,
            @Value("${corvex.imap.connection-timeout-seconds:1800}") int connectionTimeoutSeconds,
            @Value("${corvex.imap.idle-heartbeat-seconds:120}") int idleHeartbeatSeconds,
            @Value("${corvex.imap.max-connections:0}") int maxConnections,
            @Value("${corvex.imap.max-connections-per-ip:0}") int maxConnectionsPerIp) {
        this.processor = processor;
        this.metricFactory = metricFactory;
        this.enabled = enabled;
        this.bindAddress = bindAddress;
        this.plainEnabled = plainEnabled;
        this.plainPort = plainPort;
        this.starttlsEnabled = starttlsEnabled;
        this.tlsEnabled = tlsEnabled;
        this.tlsPort = tlsPort;
        this.requireTlsForAuth = requireTlsForAuth;
        this.plainAuthEnabled = plainAuthEnabled;
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.keystoreType = keystoreType;
        this.certificatePath = certificatePath;
        this.privateKeyPath = privateKeyPath;
        this.helloName = helloName;
        this.disabledCaps = disabledCaps;
        this.literalSizeLimitBytes = literalSizeLimitBytes;
        this.inMemorySizeLimitBytes = inMemorySizeLimitBytes;
        this.maxLineLength = maxLineLength;
        this.connectionTimeoutSeconds = connectionTimeoutSeconds;
        this.idleHeartbeatSeconds = idleHeartbeatSeconds;
        this.maxConnections = maxConnections;
        this.maxConnectionsPerIp = maxConnectionsPerIp;
    }

    @Override
    public synchronized void start() {
        if (!enabled) {
            running = false;
            log.info("IMAP отключён флагом CORVEX_IMAP_ENABLED=false (сервер Джеймса не стартует)");
            return;
        }
        if (!servers.isEmpty()) {
            running = true;
            return;
        }
        boolean hasTlsMaterial = hasTlsMaterial();
        List<IMAPServer> started = new ArrayList<>();
        try {
            if (plainEnabled) {
                boolean startTls = starttlsEnabled && hasTlsMaterial;
                if (starttlsEnabled && !hasTlsMaterial) {
                    log.warn("IMAP STARTTLS запрошен, но TLS-материал не задан (CORVEX_IMAP_KEYSTORE_PATH "
                            + "или CORVEX_IMAP_CERT_PATH+CORVEX_IMAP_PRIVATE_KEY_PATH): plain-слушатель стартует БЕЗ STARTTLS");
                }
                if (requireTlsForAuth && !startTls) {
                    log.warn("CORVEX_IMAP_REQUIRE_TLS_FOR_AUTH=true, а STARTTLS на plain-порту недоступен: "
                            + "LOGIN на plain будет запрещён Джеймсом до TLS");
                }
                started.add(startServer("corvex-imap-plain", plainPort, startTls, false));
            }
            if (tlsEnabled) {
                if (hasTlsMaterial) {
                    started.add(startServer("corvex-imap-tls", tlsPort, false, true));
                } else {
                    log.warn("IMAP implicit TLS пропущен: не задан TLS-материал "
                            + "(CORVEX_IMAP_KEYSTORE_PATH или CORVEX_IMAP_CERT_PATH+CORVEX_IMAP_PRIVATE_KEY_PATH)");
                }
            }
        } catch (Exception e) {
            started.forEach(server -> {
                try {
                    server.destroy();
                } catch (RuntimeException ignored) {
                    // уже останавливаемся по ошибке старта
                }
            });
            running = false;
            throw new IllegalStateException("Не удалось стартовать Apache James IMAP: " + e.getMessage(), e);
        }
        if (started.isEmpty()) {
            running = false;
            throw new IllegalStateException("IMAP включён, но не стартует ни один слушатель: проверьте "
                    + "CORVEX_IMAP_PLAIN_ENABLED/CORVEX_IMAP_TLS_ENABLED и TLS-материал");
        }
        servers.addAll(started);
        running = true;
    }

    @Override
    public synchronized void stop() {
        for (IMAPServer server : servers) {
            try {
                server.destroy();
            } catch (RuntimeException e) {
                log.warn("Ошибка остановки James IMAP Server: {}", e.getMessage());
            }
        }
        servers.clear();
        running = false;
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    @Override
    public void notifyNewMail(String mailbox, String folder) {
        // Внешняя доставка живёт в mail-storage-service/SMTP-процессе и пока не публикует событие
        // именно в локальный InVM EventBus этого IMAP-процесса. Изменения, сделанные через IMAP,
        // уже уходят в EventBus из CorvexMailboxManager/CorvexMessageManager; распределённый
        // EventBus для мгновенного внешнего IDLE — этап 5. Метод оставлен как точка порта и не
        // должен молча обещать push, которого ещё нет между процессами.
        log.debug("IMAP notifyNewMail для {} / {} получен, но межпроцессный IDLE пока не подключён", mailbox, folder);
    }

    private IMAPServer startServer(String jmxName, int port, boolean startTls, boolean socketTls) throws Exception {
        IMAPServer server = new IMAPServer(
                DefaultImapDecoderFactory.createDecoder(),
                new DefaultImapEncoderFactory().buildImapEncoder(),
                processor,
                new ImapMetrics(metricFactory),
                new NoopGaugeRegistry());
        server.setFileSystem(LocalFileSystem.INSTANCE);
        server.configure(serverConfiguration(jmxName, port, startTls, socketTls));
        server.init();
        log.info("Apache James IMAP слушает {}:{} ({}STARTTLS={}, implicitTLS={})",
                bindAddress, server.getPort(), jmxName, startTls, socketTls);
        return server;
    }

    private HierarchicalConfiguration<ImmutableNode> serverConfiguration(String jmxName, int port,
                                                                         boolean startTls, boolean socketTls)
            throws Exception {
        StringBuilder xml = new StringBuilder();
        xml.append("<configuration><imapserver enabled=\"true\">");
        element(xml, "jmxName", jmxName);
        element(xml, "bind", bindAddress + ":" + port);
        element(xml, "connectionBacklog", "200");
        element(xml, "connectionLimit", String.valueOf(Math.max(0, maxConnections)));
        element(xml, "connectionLimitPerIP", String.valueOf(Math.max(0, maxConnectionsPerIp)));
        element(xml, "timeout", String.valueOf(Math.max(1800, connectionTimeoutSeconds)));
        xml.append("<helloName autodetect=\"false\">").append(escapeXml(helloName)).append("</helloName>");
        element(xml, "enableIdle", "true");
        element(xml, "idleTimeInterval", String.valueOf(Math.max(10, idleHeartbeatSeconds)));
        element(xml, "idleTimeIntervalUnit", "SECONDS");
        element(xml, "inMemorySizeLimit", String.valueOf(Math.max(65536, inMemorySizeLimitBytes)));
        if (literalSizeLimitBytes > 0) {
            element(xml, "literalSizeLimit", String.valueOf(literalSizeLimitBytes));
        }
        element(xml, "maxLineLength", String.valueOf(Math.max(8192, maxLineLength)));
        element(xml, "disabledCaps", disabledCaps == null ? "" : disabledCaps.trim());
        xml.append("<auth>");
        element(xml, "requireSSL", String.valueOf(requireTlsForAuth));
        element(xml, "plainAuthEnabled", String.valueOf(plainAuthEnabled));
        xml.append("</auth>");
        if (startTls || socketTls) {
            xml.append("<tls startTLS=\"").append(startTls).append("\" socketTLS=\"").append(socketTls).append("\">");
            if (!isBlank(keystorePath)) {
                element(xml, "keystore", keystorePath.trim());
                element(xml, "keystoreType", isBlank(keystoreType) ? "PKCS12" : keystoreType.trim());
            } else {
                element(xml, "privateKey", privateKeyPath.trim());
                element(xml, "certificates", certificatePath.trim());
            }
            if (!isBlank(keystorePassword)) {
                element(xml, "secret", keystorePassword);
            }
            xml.append("</tls>");
        }
        element(xml, "gracefulShutdown", "false");
        xml.append("</imapserver></configuration>");

        XMLConfiguration configuration = new XMLConfiguration();
        FileHandler handler = new FileHandler(configuration);
        handler.load(new StringReader(xml.toString()));
        return configuration.configurationAt("imapserver");
    }

    private boolean hasTlsMaterial() {
        if (!isBlank(keystorePath)) {
            return materialExists(keystorePath);
        }
        return !isBlank(certificatePath) && !isBlank(privateKeyPath)
                && materialExists(certificatePath) && materialExists(privateKeyPath);
    }

    private static boolean materialExists(String configuredPath) {
        String path = configuredPath.trim();
        if (path.startsWith("classpath:")) {
            return true; // существование проверит уже Джеймс при сборке SSL-контекста
        }
        if (path.startsWith("file:///")) {
            path = "/" + path.substring("file:///".length());
        } else if (path.startsWith("file://")) {
            path = path.substring("file://".length());
        }
        return Files.isRegularFile(Path.of(path));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void element(StringBuilder xml, String name, String value) {
        xml.append('<').append(name).append('>')
                .append(escapeXml(value == null ? "" : value))
                .append("</").append(name).append('>');
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
