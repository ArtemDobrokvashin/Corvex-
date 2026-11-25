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
 * Меню «Ещё действия» убрано: «В спам» — отдельная кнопка в тулбаре,
 * «Горячие клавиши» — в «Настройках почты».
 *
 * Тест держит то, из-за чего меню и появилось: `absolute` внутри тулбара
 * обрезался его горизонтальной прокруткой, и вместо пунктов появлялась полоса
 * прокрутки. Пока в тулбаре снова не появилось меню, проверять нечего —
 * но и молча вернуть «…» обратно не получится: эти утверждения упадут.
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

async function openFirstMail() {
  await act(async () => root.render(<MemoryRouter initialEntries={['/mail']}><MailPage /></MemoryRouter>));
  await flush();

  const row = container.querySelector('[data-mail-id]') as HTMLElement | null;
  expect(row).toBeTruthy();
  await act(async () => { row!.click(); await Promise.resolve(); });
  await flush();
}

function toolbarButton(label: string) {
  return [...container.querySelectorAll('button')]
    .find((button) => button.getAttribute('aria-label') === label) as HTMLElement | undefined;
}

describe('тулбар чтения письма', () => {
  it('ставит «В спам» отдельной кнопкой, без меню «…»', async () => {
    await openFirstMail();

    expect(toolbarButton('В спам')).toBeTruthy();
    expect(toolbarButton('Ещё действия')).toBeUndefined();
    expect(document.body.querySelector('[role="menu"]')).toBeNull();
  });

  it('не дублирует «Переслать»: действие остаётся одно', async () => {
    await openFirstMail();

    const forwards = [...container.querySelectorAll('button')]
      .filter((button) => button.getAttribute('aria-label') === 'Переслать');
    // Ровно одна кнопка в тулбаре чтения. Вторая копия жила в меню «…».
    expect(forwards).toHaveLength(1);
  });

  it('не держит «Горячие клавиши» в тулбаре', async () => {
    await openFirstMail();

    const shortcut = [...container.querySelectorAll('button')]
      .find((button) => (button.textContent ?? '').includes('Горячие клавиши'));
    expect(shortcut).toBeUndefined();
  });
});
