export interface DemoReactionState {
  counts: Record<string, Record<string, number>>;
  reactedKeys: ReadonlySet<string>;
}

export function createInitialDemoReactionState(): DemoReactionState {
  return {
    counts: { c1: { '👍': 2 } },
    reactedKeys: new Set<string>(),
  };
}

export function demoReactionKey(messageId: string, emoji: string): string {
  return `${messageId}\u0000${emoji}`;
}

/**
 * Чистый переход состояния демо-реакций.
 *
 * Важно: функция не вызывает setState и не имеет других побочных эффектов.
 * React вправе повторно вызвать updater в StrictMode/dev, поэтому повторный
 * вызов с тем же предыдущим состоянием обязан давать тот же результат,
 * а не второй раз прибавлять счётчик.
 */
export function toggleDemoReaction(
  state: DemoReactionState,
  messageId: string,
  emoji: string,
): DemoReactionState {
  const key = demoReactionKey(messageId, emoji);
  const alreadyReacted = state.reactedKeys.has(key);
  const messageCounts = { ...(state.counts[messageId] ?? {}) };
  const nextCount = Math.max(0, (messageCounts[emoji] ?? 0) + (alreadyReacted ? -1 : 1));

  if (nextCount > 0) {
    messageCounts[emoji] = nextCount;
  } else {
    delete messageCounts[emoji];
  }

  const counts = { ...state.counts };
  if (Object.keys(messageCounts).length > 0) {
    counts[messageId] = messageCounts;
  } else {
    delete counts[messageId];
  }

  const reactedKeys = new Set(state.reactedKeys);
  if (alreadyReacted) {
    reactedKeys.delete(key);
  } else {
    reactedKeys.add(key);
  }

  return { counts, reactedKeys };
}
