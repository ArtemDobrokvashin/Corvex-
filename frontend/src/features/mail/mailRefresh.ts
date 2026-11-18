import type { DemoMail } from '../../api/demoData';

/** Заменяет snapshot только текущей папки, не затирая уже загруженные остальные. */
export function replaceFolderMessages(
  previous: readonly DemoMail[],
  folder: string,
  incoming: readonly DemoMail[],
): DemoMail[] {
  return [...previous.filter((mail) => mail.folder !== folder), ...incoming];
}

/**
 * Обновление первой страницы не должно выбрасывать уже подгруженные старые страницы.
 * Оставляем письма, которые старше самой старой строки свежего снимка: они пришли
 * из «Показать ещё» и сервер их в этот ответ не включает.
 */
export function mergeRefreshedFolder(
  previous: readonly DemoMail[],
  folder: string,
  incoming: readonly DemoMail[],
): DemoMail[] {
  const incomingIds = new Set(incoming.map((mail) => mail.id));
  let oldestIncomingUid = Number.POSITIVE_INFINITY;
  for (const mail of incoming) {
    if (mail.uid != null && mail.uid < oldestIncomingUid) oldestIncomingUid = mail.uid;
  }
  const olderLoaded = previous.filter(
    (mail) => mail.folder === folder
      && !incomingIds.has(mail.id)
      && mail.uid != null
      && mail.uid < oldestIncomingUid,
  );
  return [
    ...previous.filter((mail) => mail.folder !== folder),
    ...incoming,
    ...olderLoaded.sort((a, b) => b.timestamp - a.timestamp),
  ];
}

/** Курсор следующей страницы — UID самого старого письма в полученной пачке. */
export function nextPageCursor(incoming: readonly DemoMail[]): number | undefined {
  let lowest: number | undefined;
  for (const mail of incoming) {
    if (mail.uid != null && (lowest === undefined || mail.uid < lowest)) lowest = mail.uid;
  }
  return lowest;
}

/** Полная страница означает, что за ней может быть ещё одна. */
export function hasMorePages(pageLength: number, pageSize: number): boolean {
  return pageLength >= pageSize;
}

/** Новое письмо не должно вытеснять открытое; удалённое активное заменяется первым snapshot-элементом. */
export function activeMailAfterRefresh(currentId: string, incoming: readonly DemoMail[]): string {
  return incoming.some((mail) => mail.id === currentId) ? currentId : (incoming[0]?.id ?? '');
}
