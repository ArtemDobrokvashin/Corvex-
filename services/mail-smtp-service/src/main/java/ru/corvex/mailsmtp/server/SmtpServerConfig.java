package ru.corvex.mailsmtp.server;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.subethamail.smtp.server.SMTPServer;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Реализации портов из InboundMessageHandlerFactory и жизненный цикл SMTP-листенера.
 * Без этого класса контекст не собирался (нет бинов LocalDomainPolicy/RawMessageStoreClient),
 * а сам SubEtha-сервер никто не запускал — порт 2525 молчал.
 * RawMessageStore здесь — этап-2 заглушка: фиксирует получение и ставит событие в RabbitMQ,
 * физическое сохранение .eml подключит mail-storage-service по тому же интерфейсу.
 */
@Configuration
public class SmtpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(SmtpServerConfig.class);

    @Bean
    InboundMessageHandlerFactory.LocalDomainPolicy localDomainPolicy(
            @Value("${corvex.mail.domains:company.local}") String domainsCsv) {
        Set<String> domains = Stream.of(domainsCsv.split(","))
                .map(String::trim).map(String::toLowerCase).collect(Collectors.toSet());
        log.info("Local mail domains (relay allowed only to these): {}", domains);
        return address -> {
            int at = address.lastIndexOf('@');
            return at > 0 && domains.contains(address.substring(at + 1).toLowerCase().replace(">", "").trim());
        };
    }

    @Bean
    InboundMessageHandlerFactory.RawMessageStoreClient rawMessageStoreClient() {
        return eml -> {
            String rawRef = UUID.randomUUID().toString();
            log.warn("[STAGE-2 STUB] raw .eml {} bytes accepted as {} — persistent storage arrives with mail-storage integration",
                    eml.length, rawRef);
            return rawRef;
        };
    }

    @Component
    static class SmtpListener {
        private final InboundMessageHandlerFactory factory;
        private final int port;
        private final boolean enabled;
        private SMTPServer server;

        SmtpListener(InboundMessageHandlerFactory factory,
                     @Value("${corvex.smtp.inbound-port:2525}") int port,
                     @Value("${corvex.smtp.enabled:false}") boolean enabled) {
            this.factory = factory;
            this.port = port;
            this.enabled = enabled;
        }

        @PostConstruct
        void start() {
            if (!enabled) {
                log.warn("SMTP listener disabled by corvex.smtp.enabled=false");
                return;
            }
            server = new SMTPServer(factory);
            server.setHostName("mail.corvex.local");
            server.setPort(port);
            server.start();
            log.info("SMTP inbound listener started on port {} (docker maps 25->>{})", port, port);
        }

        @PreDestroy
        void stop() {
            if (server != null) {
                server.stop();
            }
        }
    }
}
