package ru.corvex.chat.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import ru.corvex.chat.security.ChatPrincipal;
import ru.corvex.chat.service.PresenceService;

import java.security.Principal;

/**
 * Связывает жизненный цикл STOMP-сессии с Redis-presence.
 * Presence намеренно не блокирует доставку сообщений: ошибки Redis обрабатываются внутри PresenceService.
 */
@Component
public class PresenceEventListener {

    private static final Logger log = LoggerFactory.getLogger(PresenceEventListener.class);

    private final PresenceService presence;

    public PresenceEventListener(PresenceService presence) {
        this.presence = presence;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        ChatPrincipal principal = principal(event.getUser());
        if (principal != null) {
            presence.onConnect(principal.userId(), sessionId(event));
        }
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        ChatPrincipal principal = principal(event.getUser());
        if (principal != null) {
            presence.heartbeat(principal.userId(), sessionId(event));
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        ChatPrincipal principal = principal(event.getUser());
        if (principal != null) {
            presence.onDisconnect(principal.userId(), event.getSessionId());
        }
    }

    private ChatPrincipal principal(Principal user) {
        if (user instanceof Authentication authentication) {
            try {
                return ChatPrincipal.from(authentication);
            } catch (RuntimeException e) {
                log.debug("Ignoring STOMP presence event with unusable principal: {}", e.getMessage());
            }
        }
        return null;
    }

    private String sessionId(Object event) {
        try {
            if (event instanceof SessionConnectedEvent connected) {
                return StompHeaderAccessor.wrap(connected.getMessage()).getSessionId();
            }
            if (event instanceof SessionSubscribeEvent subscribed) {
                return StompHeaderAccessor.wrap(subscribed.getMessage()).getSessionId();
            }
        } catch (RuntimeException ignored) {
            // PresenceService заменит null на rest-сессию.
        }
        return null;
    }
}
