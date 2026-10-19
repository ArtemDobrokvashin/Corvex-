import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

/**
 * STOMP-клиент чата. SockJS — fallback, если корпоративный прокси режет WebSocket Upgrade
 * (актуально для части парка Windows 7). Автореконнект 5 с, heartbeat 10 с — как на сервере.
 */

function parseMessage(message: IMessage): unknown | null {
  try {
    return JSON.parse(message.body) as unknown;
  } catch {
    // A malformed frame must not crash the whole chat screen; Stage 4 will add
    // schema validation and dead-letter telemetry at this boundary.
    return null;
  }
}

function clientMessageId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `client-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function connectChat(
  jwt: string,
  roomId: string,
  onMessage: (msg: unknown) => void,
): Client {
  const client = new Client({
    webSocketFactory: () => new SockJS('/ws/chat') as WebSocket,
    connectHeaders: { Authorization: `Bearer ${jwt}` },
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      const forward = (message: IMessage) => {
        const parsed = parseMessage(message);
        if (parsed !== null) onMessage(parsed);
      };
      client.subscribe(`/topic/rooms/${roomId}`, forward);
      client.subscribe('/user/queue/notifications', forward);
    },
  });
  client.activate();
  return client;
}

export function sendChatMessage(client: Client, roomId: string, bodyMarkdown: string): void {
  client.publish({
    destination: `/app/rooms/${roomId}/send`,
    body: JSON.stringify({ bodyMarkdown, clientMessageId: clientMessageId() }),
  });
}
