import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

/**
 * STOMP-клиент чата. SockJS — fallback, если корпоративный прокси режет WebSocket Upgrade
 * (актуально для части парка Windows 7). Автореконнект 5 с, heartbeat 10 с — как на сервере.
 */
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
      client.subscribe(`/topic/rooms/${roomId}`, (m: IMessage) => onMessage(JSON.parse(m.body)));
      client.subscribe('/user/queue/notifications', (m: IMessage) => onMessage(JSON.parse(m.body)));
    },
  });
  client.activate();
  return client;
}

export function sendChatMessage(client: Client, roomId: string, bodyMarkdown: string): void {
  client.publish({
    destination: `/app/rooms/${roomId}/send`,
    body: JSON.stringify({ bodyMarkdown, clientMessageId: crypto.randomUUID?.() ?? String(Date.now()) }),
  });
}
