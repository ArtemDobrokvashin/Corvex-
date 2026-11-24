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
  /**
   * Письма, по которым исход неизвестен: запрос ушёл, ответа нет. Повторять
   * вслепую нельзя, но и считать отказом нельзя — сервер мог всё сделать.
   */
  uncertain: string[];
  /** Письма, до которых очередь не дошла: после неизвестного исхода дальше не идём. */
  notAttempted: string[];
  /** Явный отказ сервера (4xx): письмо не перенесено, остальные можно продолжать. */
  rejected: string[];
  /** Первая ошибка — по ней решается, предлагать ли «Повторить». */
  error: unknown | null;
};

/**
 * Одно письмо за раз, но явный отказ на одном не отменяет остальные.
 *
 * Раньше операция останавливалась на первой ошибке: из 40 выбранных писем
 * человек получал 17 перенесённых и совет «проверьте папки перед повтором».
 * Теперь 4xx (сервер ответил и точно ничего не сделал) пропускает письмо и
 * работа продолжается, а неизвестный исход (сеть, 5xx) останавливает очередь:
 * продолжать после него — значит терять след того, что уже произошло.
 */
export async function moveSequentially(
  ids: readonly string[],
  moveOne: (id: string) => Promise<void>,
  /** Явный отказ сервера: операция точно не выполнена. */
  isRejected: (error: unknown) => boolean = () => false,
): Promise<BulkMoveResult> {
  const unique = [...new Set(ids)];
  const succeeded: string[] = [];
  const rejected: string[] = [];
  let firstError: unknown | null = null;
  for (let index = 0; index < unique.length; index += 1) {
    const id = unique[index];
    try {
      await moveOne(id);
      succeeded.push(id);
    } catch (error) {
      firstError ??= error;
      if (isRejected(error)) {
        rejected.push(id);
        continue;
      }
      return {
        succeeded,
        uncertain: [id],
        notAttempted: unique.slice(index + 1),
        rejected,
        error: firstError,
      };
    }
  }
  return { succeeded, uncertain: [], notAttempted: [], rejected, error: firstError };
}
