import type { DemoMail } from '../../api/demoData';

export type MailReadFilter = 'all' | 'unread' | 'read';
export type MailSortOrder = 'date-desc' | 'date-asc' | 'sender-asc' | 'subject-asc';

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
        case 'date-desc':
        default:
          result = mailTimestamp(right.mail) - mailTimestamp(left.mail);
          break;
      }
      return result || left.index - right.index;
    })
    .map(({ mail }) => mail);
}
