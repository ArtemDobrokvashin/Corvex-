package ru.corvex.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * STOMP поверх WebSocket с внешним брокером RabbitMQ (stomp plugin, 61613).
 * Внешний брокер — осознанный выбор вместо встроенного SimpleBroker: он позволяет поднять
 * второй экземпляр chat-service без потери сообщений между нодами и даёт очереди/ACK из коробки.
 * SockJS оставлен включённым как fallback для старых прокси/браузеров (Chrome 109 умеет
 * нативный WebSocket, но корпоративные прокси на Win7-парке иногда режут Upgrade).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${spring.rabbitmq.host:rabbitmq}")
    private String relayHost;

    @Value("${corvex.chat.allowed-origin-patterns:https://mail.company.local}")
    private List<String> allowedOriginPatterns;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat")
                .setAllowedOriginPatterns(allowedOriginPatterns.toArray(String[]::new))
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableStompBrokerRelay("/topic", "/queue")
                .setRelayHost(relayHost)
                .setRelayPort(61613)
                .setClientLogin("corvex")
                .setClientPasscode(System.getenv().getOrDefault("RABBITMQ_PASSWORD", "change-me"))
                .setSystemHeartbeatSendInterval(10_000)
                .setSystemHeartbeatReceiveInterval(10_000);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user"); // личные события: /user/queue/notifications
    }
}
