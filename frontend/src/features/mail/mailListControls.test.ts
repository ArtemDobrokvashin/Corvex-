import { describe, expect, it } from 'vitest';
import type { DemoMail } from '../../api/demoData';
import {
  applyMailListControls,
  groupMailByDate,
  hasActiveMailFilters,
  mailDateGroup,
} from './mailListControls';

function mail(overrides: Partial<DemoMail> & Pick<DemoMail, 'id'>): DemoMail {
  return {
    from: 'Sender <sender@company.local>',
    subject: 'Subject',
    snippet: 'Snippet',
    body: 'Body',
    date: 'Today',
    timestamp: 0,
    seen: true,
    flagged: false,
    hasAttachments: false,
    folder: 'INBOX',
    ...overrides,
  };
}

const mails: DemoMail[] = [
  mail({ id: 'middle', from: 'Boris <b@company.local>', subject: 'Beta report', timestamp: 200, seen: true }),
  mail({
    id: 'newest',
    from: 'Anna <a@company.local>',
    subject: 'Alpha invoice',
    timestamp: 300,
    seen: false,
    flagged: true,
    hasAttachments: true,
  }),
  mail({ id: 'oldest', from: 'Clara <c@company.local>', subject: 'Gamma plan', timestamp: 100, seen: false }),
  mail({ id: 'other-folder', folder: 'ARCHIVE', timestamp: 400, seen: false, flagged: true, hasAttachments: true }),
];

const defaults = {
  folder: 'INBOX',
  search: '',
  readFilter: 'all' as const,
  onlyFlagged: false,
  onlyAttachments: false,
  sortOrder: 'date-desc' as const,
};

describe('mail list sort and filters', () => {
  it('sorts by date in both directions without mutating the source list', () => {
    const originalOrder = mails.map(({ id }) => id);

    expect(applyMailListControls(mails, defaults).map(({ id }) => id))
      .toEqual(['newest', 'middle', 'oldest']);
    expect(applyMailListControls(mails, { ...defaults, sortOrder: 'date-asc' }).map(({ id }) => id))
      .toEqual(['oldest', 'middle', 'newest']);
    expect(mails.map(({ id }) => id)).toEqual(originalOrder);
  });

  it('sorts by sender and subject', () => {
    expect(applyMailListControls(mails, { ...defaults, sortOrder: 'sender-asc' }).map(({ id }) => id))
      .toEqual(['newest', 'middle', 'oldest']);
    expect(applyMailListControls(mails, { ...defaults, sortOrder: 'subject-asc' }).map(({ id }) => id))
      .toEqual(['newest', 'middle', 'oldest']);
  });

  it('combines read state, flagged, attachments and search filters', () => {
    const result = applyMailListControls(mails, {
      ...defaults,
      search: 'invoice',
      readFilter: 'unread',
      onlyFlagged: true,
      onlyAttachments: true,
    });

    expect(result.map(({ id }) => id)).toEqual(['newest']);
    expect(applyMailListControls(mails, { ...defaults, readFilter: 'read' }).map(({ id }) => id))
      .toEqual(['middle']);
    expect(hasActiveMailFilters({ readFilter: 'read', onlyFlagged: false, onlyAttachments: false })).toBe(true);
    expect(hasActiveMailFilters(defaults)).toBe(false);
  });
});

describe('date groups', () => {
  // Фиксированная «сейчас»: иначе тест зависит от дня запуска.
  const now = new Date(2026, 9, 19, 12, 0, 0);
  const mail = (id: string, timestamp: number) => ({ ...mails[0], id, timestamp });

  it('groups by calendar day, not by rolling 24 hours', () => {
    // 23:50 вчерашнего дня — это «Вчера», хотя прошло меньше суток.
    expect(mailDateGroup(mail('late', new Date(2026, 9, 18, 23, 50).getTime()), now)).toBe('yesterday');
    expect(mailDateGroup(mail('today', new Date(2026, 9, 19, 0, 5).getTime()), now)).toBe('today');
    expect(mailDateGroup(mail('week', new Date(2026, 9, 14, 9, 0).getTime()), now)).toBe('week');
    expect(mailDateGroup(mail('month', new Date(2026, 8, 25, 9, 0).getTime()), now)).toBe('month');
    expect(mailDateGroup(mail('older', new Date(2025, 0, 1).getTime()), now)).toBe('older');
  });

  it('keeps order inside groups and only groups date sorting', () => {
    const list = [
      mail('a', new Date(2026, 9, 19, 11, 0).getTime()),
      mail('b', new Date(2026, 9, 18, 11, 0).getTime()),
      mail('c', new Date(2026, 9, 19, 9, 0).getTime()),
    ];
    const grouped = groupMailByDate(list, 'date-desc', now);
    expect(grouped.map(({ group }) => group)).toEqual(['today', 'yesterday']);
    expect(grouped[0].mails.map(({ id }) => id)).toEqual(['a', 'c']);
    // При сортировке по отправителю заголовки дат резали бы алфавитный порядок.
    expect(groupMailByDate(list, 'sender-asc', now)).toHaveLength(1);
  });

  it('sorts unread first without losing the date order inside each half', () => {
    const list = [
      { ...mail('read-new', 300), seen: true },
      { ...mail('unread-old', 100), seen: false },
      { ...mail('read-old', 200), seen: true },
    ];
    expect(applyMailListControls(list, { ...defaults, sortOrder: 'unread-first' }).map(({ id }) => id))
      .toEqual(['unread-old', 'read-new', 'read-old']);
  });
});
