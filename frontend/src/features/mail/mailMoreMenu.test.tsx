// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mailApi, type MailFolderView, type MailMessageListItem } from '../../api/mailApi';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import MailPage from './MailPage';

/**
 * Меню «Ещё действия» должно открываться поверх тулбара, а не внутри него.
 * Тулбар прокручивается по горизонтали (`overflow-x-auto`), поэтому `absolute`
 * внутри него обрезался: вместо меню появлялась полоса прокрутки.
 *
 * Держим два свойства, которые ломались:
 *   1. меню рендерится в `document.body`, а не внутри тулбара;
 *   2. оно не растягивает прокрутку тулбара (то есть не является его потомком).
 */

const folders: MailFolderView[] = [
  { id: 'inbox-id', name: 'Inbox', specialUse: 'INBOX', total: 1, unread: 0 },
];

const mail: MailMessageListItem = {
  id: 'm-1', folderId: 'inbox-id', uid: 1, sender: 'Alice <alice@example.com>',
  subject: 'Quarterly budget', snippet: 'Draft numbers', date: '2026-04-01T10:00:00Z',
  sizeBytes: 100, seen: true, flagged: false, answered: false, hasAttachments: false,
  avStatus: 'CLEAN', spamScore: null,
};

let container: HTMLDivElement;
let root: Root;

async function flush() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); });
}

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  useLocaleStore.setState({ locale: 'ru' });
  useSession.getState().setSession({
    id: 'me', displayName: 'Me', email: 'me@example.com', groups: [], permissions: ['MAIL_READ_OWN'],
  }, false);
  vi.spyOn(mailApi, 'folders').mockResolvedValue(folders);
  vi.spyOn(mailApi, 'quota').mockResolvedValue({ usedBytes: 0, quotaBytes: 0, messageCount: 0 });
  vi.spyOn(mailApi, 'messages').mockResolvedValue([mail]);
  vi.spyOn(mailApi, 'message').mockResolvedValue({
    summary: mail, to: [], cc: [], bodyText: 'Body', bodyHtml: null, attachments: [],
  });
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

async function openMailAndMenu() {
  await act(async () => root.render(<MemoryRouter initialEntries={['/mail']}><MailPage /></MemoryRouter>));
  await flush();

  const row = container.querySelector('[data-mail-id]') as HTMLElement | null;
  expect(row).toBeTruthy();
  await act(async () => { row!.click(); await Promise.resolve(); });
  await flush();

  const more = [...container.querySelectorAll('button')]
    .find((button) => button.getAttribute('aria-label') === 'Ещё действия') as HTMLElement | undefined;
  expect(more).toBeTruthy();
  await act(async () => { more!.click(); await Promise.resolve(); });
  await flush();

  return more!;
}

describe('«Ещё действия»', () => {
  it('renders the menu into document.body, not into the scrollable toolbar', async () => {
    const more = await openMailAndMenu();

    const menu = document.body.querySelector('[role="menu"][aria-label="Ещё действия"]');
    expect(menu).toBeTruthy();

    // Меню не должно быть потомком тулбара: иначе оно снова начнёт его растягивать.
    const toolbar = more.closest('header');
    expect(toolbar).toBeTruthy();
    expect(toolbar!.contains(menu!)).toBe(false);
    expect(menu!.closest('header')).toBeNull();
  });

  it('shows the actions and closes on Escape', async () => {
    await openMailAndMenu();

    const menu = document.body.querySelector('[role="menu"][aria-label="Ещё действия"]')!;
    const items = [...menu.querySelectorAll('[role="menuitem"]')].map((item) => item.textContent ?? '');
    expect(items.join(' ')).toContain('В спам');
    expect(items.join(' ')).toContain('Горячие клавиши');

    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      await Promise.resolve();
    });
    await flush();

    expect(document.body.querySelector('[role="menu"][aria-label="Ещё действия"]')).toBeNull();
  });
});
