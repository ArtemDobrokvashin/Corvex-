// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mailApi, type MailFolderView, type SavedDraft } from '../../api/mailApi';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import MailPage from './MailPage';

const folders: MailFolderView[] = [
  { id: 'inbox-id', name: 'Inbox', specialUse: 'INBOX', total: 0, unread: 0 },
];
const saved: SavedDraft = {
  id: 'draft-7',
  revision: 3,
  updatedAt: '2026-04-03T10:00:00Z',
  payload: {
    to: 'alice@example.com',
    cc: '',
    subject: 'Quarterly plan',
    bodyText: 'Plan body',
    bodyHtml: '<p><strong>Plan body</strong></p>',
    externalAccountId: null,
    attachments: [{ fileName: 'plan.txt', contentType: 'text/plain', contentBase64: btoa('plan') }],
  },
};

let container: HTMLDivElement;
let root: Root;

async function flush() { await act(async () => { await Promise.resolve(); await Promise.resolve(); }); }
async function click(element: Element | null | undefined) {
  expect(element).toBeTruthy();
  await act(async () => { (element as HTMLElement).click(); await Promise.resolve(); });
}
function buttonWithText(text: string) {
  return [...container.querySelectorAll('button')].find((button) => button.textContent?.trim() === text);
}

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  useLocaleStore.setState({ locale: 'en' });
  useSession.getState().setSession(
    { id: 'me', displayName: 'Me', email: 'me@example.com', groups: [], permissions: ['MAIL_READ_OWN', 'MAIL_SEND_INTERNAL'] },
    false,
  );
  vi.spyOn(mailApi, 'folders').mockResolvedValue(folders);
  vi.spyOn(mailApi, 'quota').mockResolvedValue({ usedBytes: 0, quotaBytes: 0, messageCount: 0 });
  vi.spyOn(mailApi, 'messages').mockResolvedValue([]);
  vi.spyOn(mailApi, 'drafts').mockResolvedValue([
    { id: 'draft-7', revision: 3, to: 'alice@example.com', subject: 'Quarterly plan', attachmentCount: 1, sizeBytes: 4, updatedAt: saved.updatedAt },
  ]);
  vi.spyOn(mailApi, 'draft').mockResolvedValue(saved);
  vi.spyOn(mailApi, 'createDraft').mockResolvedValue(saved);
  vi.spyOn(mailApi, 'updateDraft').mockResolvedValue({ ...saved, revision: 4 });
  vi.spyOn(mailApi, 'deleteDraft').mockResolvedValue();
  vi.spyOn(mailApi, 'send').mockResolvedValue({ messageIdHeader: '<sent@example.com>', sentMessageId: 'sent-1', localRecipients: [], externalRecipients: [] });
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

describe('server drafts', () => {
  it('lists saved drafts and resumes one with its text, formatting and attachment', async () => {
    await act(async () => root.render(<MemoryRouter initialEntries={['/mail']}><MailPage /></MemoryRouter>));
    await flush();

    await click(buttonWithText('Drafts'));
    const panel = container.querySelector('[role="dialog"][aria-label="Drafts"]')!;
    expect(panel.textContent).toContain('Quarterly plan');
    expect(panel.textContent).toContain('1 att.');

    await click([...panel.querySelectorAll('button')].find((item) => item.textContent?.includes('Quarterly plan')));
    expect(mailApi.draft).toHaveBeenCalledWith('draft-7');
    expect(container.querySelector('[role="dialog"][aria-label="Drafts"]')).toBeNull();
    expect((container.querySelector('#compose-to') as HTMLInputElement).value).toBe('alice@example.com');
    expect((container.querySelector('#compose-subject') as HTMLInputElement).value).toBe('Quarterly plan');
    expect(container.querySelector('[aria-label="Message body"] strong')?.textContent).toBe('Plan body');
    expect(container.textContent).toContain('plan.txt');

    await act(async () => {
      container.querySelector('section[aria-label="New message"] form')!
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      await Promise.resolve();
    });
    await flush();
    // Отправка удаляет уже отправленный черновик по его ревизии, а не создаёт дубль.
    expect(mailApi.deleteDraft).toHaveBeenCalledWith('draft-7', 3);
    expect(mailApi.send).toHaveBeenCalledOnce();
  });
});
