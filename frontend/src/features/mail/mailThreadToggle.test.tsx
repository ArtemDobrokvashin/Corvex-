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

/**
 * Счётчик «N писем» должен раскрывать цепочку прямо под строкой.
 *
 * Что ломалось: развёрнутая цепочка рисовалась отдельным проходом после всей
 * группы, поэтому у группы из одной строки появлялся дубль строки, а при
 * выделенной строке подсветка наведения перекрывала блок — со стороны это
 * выглядело как «нажал, ничего не произошло».
 *
 * Держим три свойства:
 *   1. после нажатия письма цепочки видны (по `data-thread-mail-id`);
 *   2. строка цепочки существует ровно одна (нет дублей);
 *   3. повторное нажатие сворачивает обратно.
 */

const folders: MailFolderView[] = [
  { id: 'inbox-id', name: 'Inbox', specialUse: 'INBOX', total: 3, unread: 1 },
];

const base: MailMessageListItem = {
  id: 'm-1', folderId: 'inbox-id', uid: 1, sender: 'Twitter <info@twitter.com>',
  subject: 'You got 6 recommendations', snippet: 'Twitter > https://twitter.com/…',
  date: '2026-02-14T08:31:00Z', sizeBytes: 100, seen: true, flagged: false,
  answered: false, hasAttachments: false, avStatus: 'CLEAN', spamScore: null,
};

// Шесть писем с одинаковой темой и отправителем — одна цепочка, как в скриншоте.
const thread = Array.from({ length: 6 }, (_, index) => ({
  ...base,
  id: `m-${index + 1}`,
  uid: index + 1,
  date: `2026-02-1${index}T08:31:00Z`,
}));

let container: HTMLDivElement;
let root: Root;

async function flush() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); });
}

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  localStorage.setItem(mailPreferencesKey('me', false)!, JSON.stringify({ markRead: 'manual', conversations: true }));
  useLocaleStore.setState({ locale: 'en' });
  useSession.getState().setSession({
    id: 'me', displayName: 'Me', email: 'me@example.com', groups: [], permissions: ['MAIL_READ_OWN'],
  }, false);
  vi.spyOn(mailApi, 'folders').mockResolvedValue(folders);
  vi.spyOn(mailApi, 'quota').mockResolvedValue({ usedBytes: 0, quotaBytes: 0, messageCount: 0 });
  vi.spyOn(mailApi, 'messages').mockResolvedValue(thread);
  vi.spyOn(mailApi, 'message').mockResolvedValue({
    summary: thread[5], to: [], cc: [], bodyText: 'Body', bodyHtml: null, attachments: [],
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

async function mount() {
  await act(async () => root.render(<MemoryRouter initialEntries={['/mail']}><MailPage /></MemoryRouter>));
  await flush();
}

function counter() {
  return [...container.querySelectorAll('button')]
    .find((button) => button.getAttribute('title') === 'Expand conversation') as HTMLElement | undefined;
}

function threadRows() {
  return [...container.querySelectorAll('[data-thread-mail-id]')]
    .map((row) => row.getAttribute('data-thread-mail-id'));
}

describe('счётчик цепочки', () => {
  it('раскрывает письма под строкой по нажатию', async () => {
    await mount();
    expect(threadRows()).toEqual([]);

    const toggle = counter();
    expect(toggle).toBeTruthy();
    await act(async () => { toggle!.click(); await Promise.resolve(); });
    await flush();

    // Шесть писем цепочки, от старых к новым.
    expect(threadRows()).toEqual(['m-1', 'm-2', 'm-3', 'm-4', 'm-5', 'm-6']);
  });

  it('не рисует строку списка дважды', async () => {
    await mount();

    // До раскрытия в списке ровно одна строка — самая новая.
    expect([...container.querySelectorAll('[data-mail-id]')].map((r) => r.getAttribute('data-mail-id')))
      .toEqual(['m-6']);

    const toggle = counter()!;
    await act(async () => { toggle.click(); await Promise.resolve(); });
    await flush();

    // После раскрытия строка по-прежнему одна: дублей быть не должно.
    expect([...container.querySelectorAll('[data-mail-id]')].map((r) => r.getAttribute('data-mail-id')))
      .toEqual(['m-6']);
  });

  it('сворачивает цепочку повторным нажатием', async () => {
    await mount();

    const expand = counter()!;
    await act(async () => { expand.click(); await Promise.resolve(); });
    await flush();
    expect(threadRows().length).toBe(6);

    const collapse = container.querySelector('[data-thread-collapse]') as HTMLElement;
    expect(collapse).toBeTruthy();
    await act(async () => { collapse.click(); await Promise.resolve(); });
    await flush();

    expect(threadRows()).toEqual([]);
  });

  it('ставит кнопку «Свернуть» под письмами цепочки', async () => {
    await mount();
    await act(async () => { counter()!.click(); await Promise.resolve(); });
    await flush();

    const rows = [...container.querySelectorAll('[data-thread-mail-id]')];
    const lastRow = rows[rows.length - 1];
    // Именно кнопка под цепочкой, а не счётчик в строке: у них одинаковый title.
    const collapseButton = container.querySelector('[data-thread-collapse]')!;

    // Кнопка «Свернуть» идёт после последнего письма цепочки, а не перед ним.
    // exactly: Node.DOCUMENT_POSITION_FOLLOWING — 4; наличие других битов
    // (например, PRECEDING) означало бы обратный порядок.
    expect(lastRow.compareDocumentPosition(collapseButton)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });
});
