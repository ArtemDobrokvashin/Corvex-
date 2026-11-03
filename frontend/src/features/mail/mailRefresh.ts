import type { DemoMail } from '../../api/demoData';

/** Заменяет snapshot только текущей папки, не затирая уже загруженные остальные. */
export function replaceFolderMessages(
  previous: readonly DemoMail[],
  folder: string,
  incoming: readonly DemoMail[],
): DemoMail[] {
  return [...previous.filter((mail) => mail.folder !== folder), ...incoming];
}

/** Новое письмо не должно вытеснять открытое; удалённое активное заменяется первым snapshot-элементом. */
export function activeMailAfterRefresh(currentId: string, incoming: readonly DemoMail[]): string {
  return incoming.some((mail) => mail.id === currentId) ? currentId : (incoming[0]?.id ?? '');
}
