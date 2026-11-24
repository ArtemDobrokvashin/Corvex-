import type { DemoMail } from '../../api/demoData';

export type MailReadFilter = 'all' | 'unread' | 'read';
export type MailSortOrder = 'date-desc' | 'date-asc' | 'sender-asc' | 'subject-asc' | 'unread-first';

export interface MailListControlOptions {
  folder: string;
  search: string;
  readFilter: MailReadFilter;
  onlyFlagged: boolean;
  onlyAttachments: boolean;
  sortOrder: MailSortOrder;
}

export function hasActiveMailFilters(
  options: Pick<MailListControlOptions, 'readFilter' | 'onlyFlagged' | 'onlyAttachments'>,
): boolean {
  return options.readFilter !== 'all' || options.onlyFlagged || options.onlyAttachments;
}

function mailTimestamp(mail: DemoMail): number {
  return Number.isFinite(mail.timestamp) ? mail.timestamp : 0;
}

function compareText(left: string, right: string, locale: string): number {
  return left.localeCompare(right, locale, { sensitivity: 'base', numeric: true });
}

/**
 * Применяет папку, поиск, фильтры и сортировку к уже загруженному списку.
 * Функция чистая и не меняет исходный массив: её безопасно вызывать из useMemo
 * и повторно использовать для демо-данных и настоящего API.
 */
export function applyMailListControls(
  mails: readonly DemoMail[],
  options: MailListControlOptions,
  locale = 'ru',
): DemoMail[] {
  const query = options.search.trim().toLowerCase();
  const filtered = mails.filter((mail) => (
    mail.folder === options.folder
    && (options.readFilter === 'all'
      || (options.readFilter === 'unread' ? !mail.seen : mail.seen))
    && (!options.onlyFlagged || mail.flagged)
    && (!options.onlyAttachments || mail.hasAttachments)
    && (!query || `${mail.subject} ${mail.from} ${mail.snippet} ${mail.body}`.toLowerCase().includes(query))
  ));

  return filtered
    .map((mail, index) => ({ mail, index }))
    .sort((left, right) => {
      let result = 0;
      switch (options.sortOrder) {
        case 'date-asc':
          result = mailTimestamp(left.mail) - mailTimestamp(right.mail);
          break;
        case 'sender-asc':
          result = compareText(left.mail.from, right.mail.from, locale);
          break;
        case 'subject-asc':
          result = compareText(left.mail.subject, right.mail.subject, locale);
          break;
        case 'unread-first':
          // Внутри каждой группы непрочитанные сверху, дальше — как обычно.
          result = Number(left.mail.seen) - Number(right.mail.seen)
            || mailTimestamp(right.mail) - mailTimestamp(left.mail);
          break;
        case 'date-desc':
        default:
          result = mailTimestamp(right.mail) - mailTimestamp(left.mail);
          break;
      }
      return result || left.index - right.index;
    })
    .map(({ mail }) => mail);
}

export type MailDateGroup = 'today' | 'yesterday' | 'week' | 'month' | 'older';

/**
 * Группа для заголовка «Сегодня / Вчера / На этой неделе / В этом месяце / Ранее».
 *
 * Считается по календарным суткам локального времени, а не по «24 часа назад»:
 * письмо, пришедшее вчера в 23:50, должно быть во «Вчера», а не в «Сегодня».
 * Письма без даты (демо-данные со словами «Сейчас») попадают в «Сегодня».
 */
export function mailDateGroup(mail: DemoMail, now: Date = new Date()): MailDateGroup {
  const timestamp = mailTimestamp(mail);
  if (!timestamp) return 'today';
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const day = 24 * 60 * 60 * 1000;
  if (timestamp >= startOfToday) return 'today';
  if (timestamp >= startOfToday - day) return 'yesterday';
  if (timestamp >= startOfToday - 7 * day) return 'week';
  if (timestamp >= startOfToday - 30 * day) return 'month';
  return 'older';
}

/** Заголовки групп в порядке отображения. */
export const MAIL_DATE_GROUP_ORDER: MailDateGroup[] = ['today', 'yesterday', 'week', 'month', 'older'];

/**
 * Раскладывает уже отсортированный список по группам, сохраняя порядок внутри.
 *
 * Группировка включается только для порядка по дате: при сортировке по отправителю
 * или теме она бы резала алфавитный порядок на куски без пользы.
 */
export function groupMailByDate(
  mails: readonly DemoMail[],
  sortOrder: MailSortOrder,
  now: Date = new Date(),
): { group: MailDateGroup; mails: DemoMail[] }[] {
  if (sortOrder !== 'date-desc') return [{ group: 'today', mails: [...mails] }];
  const buckets = new Map<MailDateGroup, DemoMail[]>();
  for (const mail of mails) {
    const group = mailDateGroup(mail, now);
    const bucket = buckets.get(group);
    if (bucket) bucket.push(mail);
    else buckets.set(group, [mail]);
  }
  return MAIL_DATE_GROUP_ORDER
    .filter((group) => (buckets.get(group)?.length ?? 0) > 0)
    .map((group) => ({ group, mails: buckets.get(group)! }));
}
