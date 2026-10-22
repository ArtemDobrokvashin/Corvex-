import { describe, expect, it } from 'vitest';
import type { DemoMail } from '../../api/demoData';
import { applyMailListControls, hasActiveMailFilters } from './mailListControls';

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
