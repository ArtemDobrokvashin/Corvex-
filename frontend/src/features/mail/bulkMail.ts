/** Operations work only on the explicit snapshot shown to the user, not every message in a folder. */
export function retainVisibleSelection(selected: readonly string[], visible: readonly string[]): string[] {
  const allowed = new Set(visible);
  return selected.filter((id) => allowed.has(id));
}

export function toggleVisibleSelection(selected: readonly string[], visible: readonly string[]): string[] {
  const allowed = new Set(visible);
  const previous = new Set(selected.filter((id) => allowed.has(id)));
  if (visible.length > 0 && visible.every((id) => previous.has(id))) return [];
  return [...new Set(visible)];
}

export type BulkMoveResult = {
  succeeded: string[];
  /** The request might have reached the server even when its response was lost. */
  uncertain: string | null;
  notAttempted: string[];
  error: unknown | null;
};

/** Never retry or continue after an error: a network failure may have occurred after a move. */
export async function moveSequentially(
  ids: readonly string[],
  moveOne: (id: string) => Promise<void>,
): Promise<BulkMoveResult> {
  const unique = [...new Set(ids)];
  const succeeded: string[] = [];
  for (let index = 0; index < unique.length; index += 1) {
    try {
      await moveOne(unique[index]);
      succeeded.push(unique[index]);
    } catch (error) {
      return { succeeded, uncertain: unique[index], notAttempted: unique.slice(index + 1), error };
    }
  }
  return { succeeded, uncertain: null, notAttempted: [], error: null };
}
