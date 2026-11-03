import { describe, expect, it } from 'vitest';
import type { DemoMail } from '../../api/demoData';
import { activeMailAfterRefresh, replaceFolderMessages } from './mailRefresh';

function mail(id: string, folder = 'INBOX'): DemoMail {
  return {
    id,
    folder,
    from: 'Sender <sender@company.local>',
    subject: id,
    snippet: '',
    body: '',
    date: 'now',
    timestamp: 1,
    seen: false,
    flagged: false,
    hasAttachments: false,
  };
}

describe('mail refresh snapshots', () => {
  it('adds new incoming mail without duplicating or clearing other folders', () => {
    const previous = [mail('old'), mail('sent', 'SENT')];
    const result = replaceFolderMessages(previous, 'INBOX', [mail('new'), mail('old')]);
    expect(result.map((item) => item.id)).toEqual(['sent', 'new', 'old']);
    expect(result.filter((item) => item.id === 'old')).toHaveLength(1);
  });

  it('keeps the open message when a newer message arrives', () => {
    expect(activeMailAfterRefresh('open', [mail('new'), mail('open')])).toBe('open');
  });

  it('selects the first remaining message only when the active one disappeared', () => {
    expect(activeMailAfterRefresh('removed', [mail('first'), mail('second')])).toBe('first');
    expect(activeMailAfterRefresh('removed', [])).toBe('');
  });
});
