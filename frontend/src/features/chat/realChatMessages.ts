import type { ChatMessageView } from '../../api/chatApi';

export type LocalStatus = 'sending' | 'failed';

export interface LocalMessage extends ChatMessageView {
  localStatus?: LocalStatus;
  localFileName?: string;
}

export function compareMessages(first: ChatMessageView, second: ChatMessageView): number {
  const byTime = Date.parse(first.createdAt) - Date.parse(second.createdAt);
  return byTime !== 0 && Number.isFinite(byTime) ? byTime : first.id.localeCompare(second.id);
}

/**
 * Сливает серверную историю/события с оптимистичными локальными сообщениями.
 *
 * Серверное сообщение с настоящим UUID является подтверждением отправки, поэтому обязано
 * очищать localStatus оптимистичной копии. Иначе REST/STOMP-подтверждение заменит ID, но
 * интерфейс навсегда оставит сообщение в состоянии «Отправляется...». Локальный статус
 * сохраняется только у временных сообщений с id вида `local-*`.
 */
export function mergeMessages(existing: LocalMessage[], incoming: LocalMessage[]): LocalMessage[] {
  const byId = new Map(existing.map((message) => [message.id, message]));
  const byClient = new Map(
    existing
      .filter((message) => message.clientMessageId)
      .map((message) => [`${message.roomId}:${message.authorId}:${message.clientMessageId}`, message]),
  );

  incoming.forEach((message) => {
    const clientKey = message.clientMessageId
      ? `${message.roomId}:${message.authorId}:${message.clientMessageId}`
      : null;
    const prior = byId.get(message.id) ?? (clientKey ? byClient.get(clientKey) : undefined);
    if (prior && prior.id !== message.id) byId.delete(prior.id);
    const authoritative = !message.id.startsWith('local-');
    byId.set(message.id, {
      ...prior,
      ...message,
      localStatus: authoritative ? undefined : (message.localStatus ?? prior?.localStatus),
      localFileName: message.localFileName ?? prior?.localFileName,
    });
    if (clientKey) byClient.set(clientKey, byId.get(message.id) as LocalMessage);
  });

  return [...byId.values()].sort(compareMessages);
}
