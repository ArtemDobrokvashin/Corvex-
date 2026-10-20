package ru.corvex.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import ru.corvex.chat.ws.StompAuthChannelInterceptor;

import java.util.List;

/**
 * STOMP поверх WebSocket с внешним брокером RabbitMQ (STOMP plugin, 61613).
 * Внешний брокер позволяет второму экземпляру chat-service получать те же топики без
 * локального SimpleBroker. SockJS оставлен как fallback для корпоративных прокси,
 * которые режут WebSocket Upgrade на старом парке Windows 7/Chrome 109.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authInterceptor;

    @Value("${spring.rabbitmq.host:rabbitmq}")
    private String relayHost;

    @Value("${spring.rabbitmq.username:corvex}")
    private String relayUsername;

    @Value("${spring.rabbitmq.password:change-me}")
    private String relayPassword;

    @Value("${corvex.chat.allowed-origin-patterns:https://mail.company.local}")
    private List<String> allowedOriginPatterns;

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat")
                .setAllowedOriginPatterns(allowedOriginPatterns.toArray(String[]::new))
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableStompBrokerRelay("/topic", "/queue")
                .setRelayHost(relayHost)
                .setRelayPort(61613)
                .setClientLogin(relayUsername)
                .setClientPasscode(relayPassword)
                .setSystemLogin(relayUsername)
                .setSystemPasscode(relayPassword)
                .setSystemHeartbeatSendInterval(10_000)
                .setSystemHeartbeatReceiveInterval(10_000);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
    }
}
