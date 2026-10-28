import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { ChatMessageKind } from './chatApi';

/**
 * STOMP-клиент чата. SockJS — fallback, если корпоративный прокси режет WebSocket Upgrade
 * (актуально для части парка Windows 7). Автореконнект 5 с, heartbeat 10 с — как на сервере.
 *
 * Access-token берётся через provider перед каждым CONNECT: JWT живёт 15 минут, а STOMP-клиент
 * может переживать несколько ротаций refresh-сессии и не должен навсегда остаться со старым Bearer.
 */

function parseMessage(message: IMessage): unknown | null {
  try {
    return JSON.parse(message.body) as unknown;
  } catch {
    // Один битый frame не должен ронять весь экран чата; серверная история остаётся источником истины.
    return null;
  }
}

export function newClientMessageId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `client-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export type ChatTokenProvider = string | (() => string | null);

function resolveToken(provider: ChatTokenProvider): string {
  return (typeof provider === 'function' ? provider() : provider) ?? '';
}

export function connectChat(
  token: ChatTokenProvider,
  roomId: string,
  onMessage: (msg: unknown) => void,
  onStatusChange?: (connected: boolean) => void,
): Client {
  const client = new Client({
    webSocketFactory: () => new SockJS('/ws/chat') as WebSocket,
    connectHeaders: { Authorization: `Bearer ${resolveToken(token)}` },
    beforeConnect: () => {
      client.connectHeaders = { Authorization: `Bearer ${resolveToken(token)}` };
    },
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      onStatusChange?.(true);
      const forward = (message: IMessage) => {
        const parsed = parseMessage(message);
        if (parsed !== null) onMessage(parsed);
      };
      client.subscribe(`/topic/rooms.${roomId}`, forward);
      client.subscribe('/user/queue/notifications', forward);
      client.subscribe('/user/queue/errors', forward);
    },
    onDisconnect: () => onStatusChange?.(false),
    onWebSocketClose: () => onStatusChange?.(false),
    onStompError: () => onStatusChange?.(false),
  });
  client.activate();
  return client;
}

export interface SendChatMessageOptions {
  clientMessageId?: string;
  threadRootId?: string | null;
  kind?: ChatMessageKind | null;
  fileRef?: string | null;
  mentionedUserIds?: string[];
}

export function sendChatMessage(
  client: Client,
  roomId: string,
  bodyMarkdown: string | null,
  options: SendChatMessageOptions = {},
): string {
  const clientMessageId = options.clientMessageId ?? newClientMessageId();
  client.publish({
    destination: `/app/rooms/${roomId}/send`,
    body: JSON.stringify({
      bodyMarkdown,
      clientMessageId,
      threadRootId: options.threadRootId ?? null,
      kind: options.kind ?? null,
      fileRef: options.fileRef ?? null,
      mentionedUserIds: options.mentionedUserIds ?? [],
    }),
  });
  return clientMessageId;
}

export function sendTyping(client: Client | null, roomId: string): void {
  if (!client?.connected) return;
  client.publish({ destination: `/app/rooms/${roomId}/typing`, body: '{}' });
}

export function sendRead(client: Client | null, roomId: string, lastReadMessageId: string): void {
  if (!client?.connected) return;
  client.publish({
    destination: `/app/rooms/${roomId}/read`,
    body: JSON.stringify({ lastReadMessageId }),
  });
}
