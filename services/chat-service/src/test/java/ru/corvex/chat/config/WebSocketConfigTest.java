package ru.corvex.chat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.StompBrokerRelayRegistration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import ru.corvex.chat.ws.StompAuthChannelInterceptor;

import java.util.List;

import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketConfigTest {

    private StompAuthChannelInterceptor interceptor;
    private WebSocketConfig config;

    @BeforeEach
    void setUp() {
        interceptor = mock(StompAuthChannelInterceptor.class);
        config = new WebSocketConfig(interceptor);
        ReflectionTestUtils.setField(config, "relayHost", "rabbitmq.internal");
        ReflectionTestUtils.setField(config, "relayUsername", "chat-user");
        ReflectionTestUtils.setField(config, "relayPassword", "chat-password");
        ReflectionTestUtils.setField(config, "allowedOriginPatterns",
                List.of("https://mail.company.local"));
    }

    @Test
    void registersSockJsEndpointWithConfiguredOrigins() {
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration endpoint =
                mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint("/ws/chat")).thenReturn(endpoint);
        when(endpoint.setAllowedOriginPatterns("https://mail.company.local")).thenReturn(endpoint);

        config.registerStompEndpoints(registry);

        verify(endpoint).setAllowedOriginPatterns("https://mail.company.local");
        verify(endpoint).withSockJS();
    }

    @Test
    void registersInboundAuthenticationInterceptor() {
        ChannelRegistration registration = mock(ChannelRegistration.class);

        config.configureClientInboundChannel(registration);

        verify(registration).interceptors(interceptor);
    }

    @Test
    void configuresRabbitRelayAndDestinationPrefixes() {
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);
        StompBrokerRelayRegistration relay =
                mock(StompBrokerRelayRegistration.class, RETURNS_SELF);
        when(registry.enableStompBrokerRelay("/topic", "/queue")).thenReturn(relay);

        config.configureMessageBroker(registry);

        verify(relay).setRelayHost("rabbitmq.internal");
        verify(relay).setRelayPort(61613);
        verify(relay).setClientLogin("chat-user");
        verify(relay).setClientPasscode("chat-password");
        verify(relay).setSystemLogin("chat-user");
        verify(relay).setSystemPasscode("chat-password");
        verify(relay).setSystemHeartbeatSendInterval(10_000);
        verify(relay).setSystemHeartbeatReceiveInterval(10_000);
        verify(registry).setApplicationDestinationPrefixes("/app");
        verify(registry).setUserDestinationPrefix("/user");
        verify(registry).setPreservePublishOrder(true);
    }
}
