// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mailApi, type MailFolderView, type MailMessageListItem } from '../../api/mailApi';
import { contactsApi } from '../../api/contactsApi';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import MailPage from './MailPage';

/**
 * «В контакты» не давала понять, что нажатие сработало: кнопка оставалась
 * прежней, и человек жал её второй раз. После добавления она должна менять
 * вид на «В контактах» и больше не быть кнопкой.
 *
 * Держим два свойства:
 *   1. адрес уже в книге → сразу «В контактах», без запроса;
 *   2. адреса нет → нажатие добавляет его и переключает вид.
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

function contactState(state: 'idle' | 'saved') {
  return container.querySelector(`[data-contact-state="${state}"]`);
}

/** Состояние кнопки видно только после загрузки адресной книги. */
async function waitForContactState(state: 'idle' | 'saved') {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const found = contactState(state);
    if (found) return found;
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 5)); });
  }
  return null;
}

describe('«В контакты»', () => {
  it('показывает «В контактах», если адрес уже в книге', async () => {
    vi.spyOn(contactsApi, 'list').mockResolvedValue([
      {
        id: '11111111-1111-4111-8111-111111111111',
        displayName: 'Alice',
        email: 'alice@example.com',
        phone: null, organization: null, jobTitle: null, note: null, groupName: null,
        source: 'MANUAL', createdAt: '2026-04-01T10:00:00Z', updatedAt: '2026-04-01T10:00:00Z',
      },
    ]);
    const create = vi.spyOn(contactsApi, 'create');

    await openFirstMail();

    // Кнопка «В контакты» тут же становится отметкой, а не ждёт нажатия.
    const saved = await waitForContactState('saved');
    expect(saved?.textContent).toContain('В контактах');
    expect(contactState('idle')).toBeNull();

    // И нажимать нечего: запроса на создание не было.
    expect(create).not.toHaveBeenCalled();
  });

  it('после нажатия меняет вид на «В контактах»', async () => {
    vi.spyOn(contactsApi, 'list').mockResolvedValue([]);
    const create = vi.spyOn(contactsApi, 'create').mockResolvedValue({
      id: '22222222-2222-4222-8222-222222222222',
      displayName: 'Alice', email: 'alice@example.com',
      phone: null, organization: null, jobTitle: null, note: null, groupName: null,
      source: 'MANUAL', createdAt: '2026-04-01T10:00:00Z', updatedAt: '2026-04-01T10:00:00Z',
    });

    await openFirstMail();

    const button = contactState('idle') as HTMLElement | null;
    expect(button).toBeTruthy();
    expect(button!.textContent).toContain('В контакты');
    expect(button!.tagName).toBe('BUTTON');

    await act(async () => { button!.click(); await Promise.resolve(); });
    await flush();

    expect(create).toHaveBeenCalledWith({ displayName: 'Alice', email: 'alice@example.com' });
    expect(contactState('saved')?.textContent).toContain('В контактах');
    expect(contactState('idle')).toBeNull();
  });
});
