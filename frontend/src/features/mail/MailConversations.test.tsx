// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mailApi, type MailFolderView, type MailMessageListItem } from '../../api/mailApi';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import { mailPreferencesKey } from './mailPreferences';
import MailPage from './MailPage';

const folders: MailFolderView[] = [
  { id: 'inbox-id', name: 'Inbox', specialUse: 'INBOX', total: 3, unread: 1 },
  { id: 'sent-id', name: 'Sent', specialUse: 'SENT', total: 0, unread: 0 },
];

const first: MailMessageListItem = {
  id: 'm-1', folderId: 'inbox-id', uid: 1, sender: 'Alice <alice@example.com>',
  subject: 'Quarterly budget', snippet: 'Draft numbers', date: '2026-04-01T10:00:00Z',
  sizeBytes: 100, seen: true, flagged: false, answered: false, hasAttachments: false,
  avStatus: 'CLEAN', spamScore: null,
};
const reply: MailMessageListItem = {
  ...first, id: 'm-2', uid: 2, subject: 'Re: Quarterly budget', snippet: 'Approved',
  date: '2026-04-02T10:00:00Z', seen: false, sizeBytes: 120,
};
const unrelated: MailMessageListItem = {
  ...first, id: 'm-3', uid: 3, sender: 'Bob <bob@example.com>', subject: 'Lunch',
  snippet: 'At noon', date: '2026-04-03T10:00:00Z', seen: true,
};

let container: HTMLDivElement;
let root: Root;

async function flush() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); });
}

async function mount(conversations: boolean) {
  localStorage.setItem(mailPreferencesKey('me', false)!, JSON.stringify({ markRead: 'manual', conversations }));
  await act(async () => root.render(<MemoryRouter initialEntries={['/mail']}><MailPage /></MemoryRouter>));
  await flush();
}

function rowIds() {
  return [...container.querySelectorAll('[data-mail-id]')].map((row) => row.getAttribute('data-mail-id'));
}

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  useLocaleStore.setState({ locale: 'en' });
  useSession.getState().setSession({
    id: 'me', displayName: 'Me', email: 'me@example.com', groups: [], permissions: ['MAIL_READ_OWN'],
  }, false);
  vi.spyOn(mailApi, 'folders').mockResolvedValue(folders);
  vi.spyOn(mailApi, 'quota').mockResolvedValue({ usedBytes: 0, quotaBytes: 0, messageCount: 0 });
  vi.spyOn(mailApi, 'messages').mockImplementation(async (folderId) => (
    folderId === 'inbox-id' ? [unrelated, reply, first] : []
  ));
  vi.spyOn(mailApi, 'message').mockResolvedValue({
    summary: first, to: [], cc: [], bodyText: 'Body', bodyHtml: null, attachments: [],
  });
  vi.spyOn(mailApi, 'flags').mockImplementation(async (_id, flags) => ({ ...first, ...flags }));
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  localStorage.clear();
  useSession.getState().clearSession();
  vi.restoreAllMocks();
});

describe('conversation view', () => {
  it('collapses a reply into the newest row and keeps unrelated mail separate', async () => {
    await mount(true);

    // Три письма, из них два — одна переписка: в списке две строки.
    expect(rowIds()).toEqual(['m-3', 'm-2']);
    expect(container.textContent).toContain('Quarterly budget');
    expect(container.textContent).toContain('2 messages');
    // Префикса `Re:` в строке нет: он уже не несёт смысла.
    expect(rowIds().length).toBe(2);
  });

  it('expands a thread into its individual letters on demand', async () => {
    await mount(true);
    const toggle = [...container.querySelectorAll('button')]
      .find((button) => button.getAttribute('title') === 'Expand conversation');
    expect(toggle).toBeTruthy();

    await act(async () => { (toggle as HTMLElement).click(); await Promise.resolve(); });

    const threadRows = [...container.querySelectorAll('[data-thread-mail-id]')]
      .map((row) => row.getAttribute('data-thread-mail-id'));
    // Внутри — от старого к новому, как читается переписка.
    expect(threadRows).toEqual(['m-1', 'm-2']);
  });

  it('keeps the flat list when the preference is off', async () => {
    await mount(false);
    expect(rowIds()).toEqual(['m-3', 'm-2', 'm-1']);
    expect(container.textContent).not.toContain('2 messages');
  });
});
