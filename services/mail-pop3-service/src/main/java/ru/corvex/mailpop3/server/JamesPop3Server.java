package ru.corvex.mailpop3.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.protocols.api.handler.CommandDispatcher;
import org.apache.james.protocols.api.handler.CommandHandlerResultLogger;
import org.apache.james.protocols.api.handler.ProtocolHandler;
import org.apache.james.protocols.netty.BasicChannelInboundHandler;
import org.apache.james.protocols.netty.ProtocolMDCContextFactory;
import org.apache.james.protocols.pop3.POP3Configuration;
import org.apache.james.protocols.pop3.POP3Protocol;
import org.apache.james.protocols.pop3.POP3Session;
import org.apache.james.protocols.pop3.core.CapaCmdHandler;
import org.apache.james.protocols.pop3.core.DeleCmdHandler;
import org.apache.james.protocols.pop3.core.ListCmdHandler;
import org.apache.james.protocols.pop3.core.NoopCmdHandler;
import org.apache.james.protocols.pop3.core.QuitCmdHandler;
import org.apache.james.protocols.pop3.core.RetrCmdHandler;
import org.apache.james.protocols.pop3.core.RsetCmdHandler;
import org.apache.james.protocols.pop3.core.StatCmdHandler;
import org.apache.james.protocols.pop3.core.TopCmdHandler;
import org.apache.james.protocols.pop3.core.UidlCmdHandler;
import org.apache.james.protocols.pop3.core.UnknownCmdHandler;
import org.apache.james.protocols.pop3.core.UserCmdHandler;
import org.apache.james.protocols.pop3.core.WelcomeMessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import ru.corvex.mailpop3.client.Pop3AuthClient;
import ru.corvex.mailpop3.client.ProtocolStorageClient;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;

/**
 * Bootstrap Apache James POP3 без Guice: тот же набор core-хендлеров, что в
 * james-server-protocols-pop3 (CoreCmdHandlerLoader), но собранный вручную вокруг
 * POP3Protocol + BasicChannelInboundHandler (james protocols-netty). Corvex-части —
 * только CorvexPassCmdHandler/CorvexPop3Mailbox поверх mail-storage-service.
 *
 * Политика транспорта: STLS в v1 не анонсируется (нет StlsCmdHandler в цепочке);
 * клиентам предлагается implicit TLS 995. Открытый 110 биндится только при явном
 * CORVEX_POP3_PLAIN_ENABLED=true — пароль POP3 ходит в открытом виде.
 * Сервер выключен по умолчанию (CORVEX_POP3_ENABLED) до прохождения внешних gates.
 */
@Component
public class JamesPop3Server implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JamesPop3Server.class);
    private static final int MAX_LINE_BYTES = 8192; // POP3-команды однострочные; тела писем сюда не попадают

    private final Pop3AuthClient auth;
    private final ProtocolStorageClient storage;
    private final boolean enabled;
    private final boolean plainEnabled;
    private final int plainPort;
    private final int tlsPort;
    private final String helloName;
    private final String keystorePath;
    private final String keystorePassword;
    private final int maxMessagesPerSession;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private final List<Channel> channels = new ArrayList<>();
    private volatile boolean running;

    public JamesPop3Server(Pop3AuthClient auth,
                           ProtocolStorageClient storage,
                           @Value("${corvex.pop3.enabled:false}") boolean enabled,
                           @Value("${corvex.pop3.plain-enabled:false}") boolean plainEnabled,
                           @Value("${corvex.pop3.plain-port:1110}") int plainPort,
                           @Value("${corvex.pop3.tls-port:1995}") int tlsPort,
                           @Value("${corvex.pop3.hello-name:localhost}") String helloName,
                           @Value("${corvex.pop3.keystore-path:}") String keystorePath,
                           @Value("${corvex.pop3.keystore-password:}") String keystorePassword,
                           @Value("${corvex.pop3.max-messages-per-session:1000}") int maxMessagesPerSession) {
        this.auth = auth;
        this.storage = storage;
        this.enabled = enabled;
        this.plainEnabled = plainEnabled;
        this.plainPort = plainPort;
        this.tlsPort = tlsPort;
        this.helloName = helloName;
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.maxMessagesPerSession = maxMessagesPerSession;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!enabled) {
            log.warn("POP3-сервер выключен (CORVEX_POP3_ENABLED=false): слушатели 110/995 не поднимаются");
            return;
        }
        try {
            POP3Protocol protocol = buildProtocol();
            boss = new NioEventLoopGroup(1);
            worker = new NioEventLoopGroup();
            if (plainEnabled) {
                channels.add(bind(protocol, plainPort, null));
                log.warn("POP3 plain-слушатель поднят на порту {} (пароль в открытом виде!)", plainPort);
            }
            SslContext ssl = buildSslContextOrNull();
            if (ssl != null) {
                channels.add(bind(protocol, tlsPort, ssl));
                log.info("POP3 implicit-TLS слушатель поднят на порту {}", tlsPort);
            }
            if (channels.isEmpty()) {
                log.error("POP3 включён, но ни одного слушателя: plain выключен и не задан keystore для TLS");
                shutdownGroups();
                return;
            }
            running = true;
        } catch (Exception e) {
            // Не роняем весь сервис (actuator/health должны жить), но громко фиксируем нерабочий протокол.
            log.error("POP3-сервер не поднялся: {}", e.getMessage(), e);
            stop();
        }
    }

    private Channel bind(POP3Protocol protocol, int port, SslContext ssl) throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (ssl != null) {
                            ch.pipeline().addLast(ssl.newHandler(ch.alloc()));
                        }
                        ch.pipeline().addLast(new DelimiterBasedFrameDecoder(MAX_LINE_BYTES, Delimiters.lineDelimiter()));
                        ch.pipeline().addLast(new BasicChannelInboundHandler(
                                new ProtocolMDCContextFactory.Standard(), protocol));
                    }
                });
        return bootstrap.bind(port).sync().channel();
    }

    POP3Protocol buildProtocol() throws Exception {
        MetricFactory metrics = NoopMetricFactory.INSTANCE;
        List<ProtocolHandler> handlers = List.of(
                new WelcomeMessageHandler(),
                new CommandDispatcher<POP3Session>(List.of()),
                new CapaCmdHandler(metrics),
                new UserCmdHandler(metrics),
                new CorvexPassCmdHandler(metrics, auth, storage, maxMessagesPerSession),
                new ListCmdHandler(metrics),
                new UidlCmdHandler(metrics),
                new RsetCmdHandler(metrics),
                new DeleCmdHandler(metrics),
                new NoopCmdHandler(metrics),
                new RetrCmdHandler(metrics),
                new TopCmdHandler(metrics),
                new StatCmdHandler(metrics),
                new QuitCmdHandler(metrics),
                new UnknownCmdHandler(),
                new CommandHandlerResultLogger());
        return new POP3Protocol(new ListProtocolHandlerChain(handlers), new CorvexPop3Configuration(helloName));
    }

    private SslContext buildSslContextOrNull() throws Exception {
        if (keystorePath == null || keystorePath.isBlank() || !Files.exists(Path.of(keystorePath))) {
            log.warn("POP3 TLS не поднят: keystore {} не найден (PEM->PKCS12 конвертация — в README сервиса)",
                    keystorePath);
            return null;
        }
        char[] password = keystorePassword == null ? new char[0] : keystorePassword.toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(keystorePath))) {
            keyStore.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        return SslContextBuilder.forServer(kmf).build();
    }

    @Override
    public synchronized void stop() {
        channels.forEach(c -> c.close().awaitUninterruptibly());
        channels.clear();
        shutdownGroups();
        running = false;
    }

    private void shutdownGroups() {
        if (boss != null) {
            boss.shutdownGracefully();
            boss = null;
        }
        if (worker != null) {
            worker.shutdownGracefully();
            worker = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Баннер/приветствие Corvex поверх дефолтов James POP3Configuration. */
    static final class CorvexPop3Configuration extends POP3Configuration {
        private final String helloName;

        CorvexPop3Configuration(String helloName) {
            this.helloName = helloName;
        }

        @Override
        public String getHelloName() {
            return helloName;
        }

        @Override
        public String getSoftwareName() {
            return "Corvex POP3";
        }

        @Override
        public String getGreeting() {
            return "Corvex POP3 ready";
        }
    }
}
