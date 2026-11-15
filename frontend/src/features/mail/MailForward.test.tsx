// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mailApi, type MailFolderView, type MailMessageDetail, type MailMessageListItem } from '../../api/mailApi';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import MailPage from './MailPage';

const folders: MailFolderView[] = [
  { id: 'inbox-id', name: 'Inbox', specialUse: 'INBOX', total: 2, unread: 2 },
  { id: 'sent-id', name: 'Sent', specialUse: 'SENT', total: 0, unread: 0 },
];

const summary = (id: string, subject: string, sender: string): MailMessageListItem => ({
  id, folderId: 'inbox-id', uid: 1, sender, subject, snippet: 'preview',
  date: '2026-04-03T10:00:00Z', sizeBytes: 100, seen: false, flagged: false, answered: false,
  hasAttachments: true, avStatus: 'CLEAN', spamScore: null,
});

const first = summary('m1', 'Contract', 'Alice <alice@example.com>');
const second = summary('m2', 'Invoice', 'bob@example.com');

const detail = (item: MailMessageListItem, bodyText: string, attachments: MailMessageDetail['attachments']): MailMessageDetail => ({
  summary: item, to: ['me@example.com'], cc: [], bodyText, bodyHtml: null, attachments,
});

const clean = { partIndex: 0, sha256: 'a', fileName: 'contract.pdf', sizeBytes: 120, contentType: 'application/pdf', avStatus: 'CLEAN' };
const infected = { partIndex: 1, sha256: 'b', fileName: 'invoice.exe', sizeBytes: 120, contentType: 'application/octet-stream', avStatus: 'INFECTED' };
const unverified = { partIndex: 2, sha256: 'c', fileName: 'notes.txt', sizeBytes: 12, contentType: 'text/plain', avStatus: 'UNVERIFIED' };

let container: HTMLDivElement;
let root: Root;

async function flush() { await act(async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); }); }
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
  vi.spyOn(mailApi, 'quota').mockResolvedValue({ usedBytes: 0, quotaBytes: 0, messageCount: 2 });
  vi.spyOn(mailApi, 'messages').mockResolvedValue([first, second]);
  vi.spyOn(mailApi, 'message').mockImplementation(async (id) => id === 'm1'
    ? detail(first, 'Full contract text.', [clean, infected])
    : detail(second, 'Full invoice text.', [unverified]));
  vi.spyOn(mailApi, 'downloadAttachment').mockResolvedValue(new Blob(['pdf-bytes'], { type: 'application/pdf' }));
  vi.spyOn(mailApi, 'createDraft').mockResolvedValue({ id: 'draft-1', revision: 1, payload: {} as never, updatedAt: '2026-01-01T00:00:00Z' });
  vi.spyOn(mailApi, 'deleteDraft').mockResolvedValue();
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

describe('group forward', () => {
  it('forwards full originals with verified attachments and reports the refused ones', async () => {
    await act(async () => root.render(<MemoryRouter initialEntries={['/mail']}><MailPage /></MemoryRouter>));
    await flush();

    await click(container.querySelector('input[aria-label="Select message: Contract"]'));
    await click(container.querySelector('input[aria-label="Select message: Invoice"]'));
    await click(buttonWithText('Forward selected'));
    await flush();

    // Тело содержит оригиналы целиком: отправитель, дата, тема и полный текст обоих писем.
    const body = container.querySelector('[aria-label="Message body"]')?.textContent ?? '';
    expect(body).toContain('Full contract text.');
    expect(body).toContain('Full invoice text.');
    expect(body).toContain('From: Alice <alice@example.com>');
    expect(body).toContain('Subject: Invoice');
    expect((container.querySelector('#compose-subject') as HTMLInputElement).value).toBe('Fwd: (2) Contract');

    // Скачивается и прикладывается только проверенный файл.
    expect(mailApi.downloadAttachment).toHaveBeenCalledOnce();
    expect(mailApi.downloadAttachment).toHaveBeenCalledWith('m1', 0);
    expect(container.textContent).toContain('contract.pdf');

    // Отказы не проглатываются: пользователь видит, что именно не ушло и почему.
    const notice = container.querySelector('[role="status"]')?.textContent ?? '';
    expect(notice).toContain('invoice.exe');
    expect(notice).toContain('blocked by antivirus');
    expect(notice).toContain('notes.txt');
    expect(notice).toContain('not verified by antivirus');
  });

  it('never sends a partial batch when the original cannot be loaded', async () => {
    // Один из оригиналов недоступен — всю пачку не отправляем, а не «что успели».
    vi.mocked(mailApi.message).mockRejectedValue(new Error('mailbox unavailable'));
    await act(async () => root.render(<MemoryRouter initialEntries={['/mail']}><MailPage /></MemoryRouter>));
    await flush();

    await click(container.querySelector('input[aria-label="Select message: Contract"]'));
    await click(buttonWithText('Forward selected'));
    await flush();

    expect(container.querySelector('section[aria-label="New message"]')).toBeNull();
    expect(container.textContent).toContain('Could not load the selected messages for forwarding.');
    expect(mailApi.downloadAttachment).not.toHaveBeenCalled();
  });
});
