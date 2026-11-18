import { describe, expect, it } from 'vitest';
import type { DemoMail } from '../../api/demoData';
import {
  activeMailAfterRefresh,
  hasMorePages,
  mergeRefreshedFolder,
  nextPageCursor,
  replaceFolderMessages,
} from './mailRefresh';

function mail(id: string, folder = 'INBOX', uid = 1): DemoMail {
  return {
    id,
    uid,
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

describe('mail list pagination', () => {
  it('keeps older pages when the first page is refreshed', () => {
    const previous = [mail('inbox-3', 'INBOX', 30), mail('inbox-1', 'INBOX', 10), mail('sent', 'SENT', 5)];
    const refreshed = mergeRefreshedFolder(previous, 'INBOX', [mail('inbox-4', 'INBOX', 40), mail('inbox-3', 'INBOX', 30)]);
    expect(refreshed.map((item) => item.id).sort()).toEqual(['inbox-1', 'inbox-3', 'inbox-4', 'sent']);
  });

  it('keeps mail that slid below the refreshed window when a new message arrives', () => {
    // Пришло письмо uid 40: первая страница теперь 40..31, а uid 30 выпал из неё.
    // Без этого правила между страницами образовалась бы дыра.
    const previous = [mail('inbox-3', 'INBOX', 30), mail('inbox-2', 'INBOX', 20)];
    const refreshed = mergeRefreshedFolder(previous, 'INBOX', [mail('inbox-4', 'INBOX', 40)]);
    expect(refreshed.map((item) => item.id).sort()).toEqual(['inbox-2', 'inbox-3', 'inbox-4']);
  });

  it('drops a message that vanished from the newer part of the folder', () => {
    // uid 30 новее окна 10..1 и в ответе отсутствует — значит письма в папке больше нет.
    const previous = [mail('inbox-3', 'INBOX', 30)];
    const refreshed = mergeRefreshedFolder(previous, 'INBOX', [mail('inbox-1', 'INBOX', 10)]);
    expect(refreshed.map((item) => item.id)).toEqual(['inbox-1']);
  });

  it('restarts the page without duplicating messages already shown', () => {
    const previous = [mail('inbox-2', 'INBOX', 20)];
    const refreshed = mergeRefreshedFolder(previous, 'INBOX', [mail('inbox-2', 'INBOX', 20)]);
    expect(refreshed.map((item) => item.id)).toEqual(['inbox-2']);
  });

  it('uses the oldest uid as the next cursor and stops on a short page', () => {
    expect(nextPageCursor([mail('a', 'INBOX', 50), mail('b', 'INBOX', 1)])).toBe(1);
    expect(nextPageCursor([])).toBeUndefined();
    expect(hasMorePages(50, 50)).toBe(true);
    expect(hasMorePages(12, 50)).toBe(false);
  });
});
