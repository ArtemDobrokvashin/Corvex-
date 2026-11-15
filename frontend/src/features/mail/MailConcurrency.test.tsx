// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/httpClient';
import { mailApi, type MailFolderView, type MailMessageListItem } from '../../api/mailApi';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import { mailPreferencesKey } from './mailPreferences';
import MailPage from './MailPage';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
}

const folders: MailFolderView[] = [
  { id: 'inbox-id', name: 'Inbox', specialUse: 'INBOX', total: 1, unread: 1 },
  { id: 'sent-id', name: 'Sent', specialUse: 'SENT', total: 0, unread: 0 },
  { id: 'trash-id', name: 'Trash', specialUse: 'TRASH', total: 0, unread: 0 },
];
const message: MailMessageListItem = {
  id: 'message-1', folderId: 'inbox-id', uid: 1, sender: 'Alice <alice@example.com>',
  subject: 'Important source message', snippet: 'Preview', date: '2026-04-03T10:00:00Z',
  sizeBytes: 200, seen: false, flagged: false, answered: false, hasAttachments: false,
  avStatus: 'CLEAN', spamScore: null,
};
const moved = { ...message, folderId: 'trash-id' };

let container: HTMLDivElement;
let root: Root;

async function flush() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); });
}
async function mount() {
  await act(async () => root.render(<MemoryRouter initialEntries={['/mail']}><MailPage /></MemoryRouter>));
  await flush();
}
async function click(element: Element | null | undefined) {
  expect(element).toBeTruthy();
  await act(async () => { (element as HTMLElement).click(); await Promise.resolve(); });
}
function folderButton(name: string) {
  return [...container.querySelectorAll('nav[aria-label="Mail folders"] button')]
    .find((button) => button.querySelector('span')?.textContent === name)!;
}
function setInput(element: HTMLInputElement | HTMLTextAreaElement, value: string) {
  const prototype = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(prototype, 'value')!.set!.call(element, value);
  element.dispatchEvent(new Event('input', { bubbles: true }));
}
/** Композер — визуальный редактор, поэтому текст задаётся через поддерживаемый HTML-режим. */
async function setBody(value: string) {
  await click(container.querySelector('button[aria-label="HTML source"]'));
  await act(async () => {
    setInput(container.querySelector('textarea[aria-label="Message HTML source"]')!, `<p>${value}</p>`);
    await Promise.resolve();
  });
}
async function sendDraft() {
  await click([...container.querySelectorAll('button')].find((button) => button.textContent?.trim() === 'Compose'));
  await act(async () => {
    setInput(container.querySelector('#compose-to')!, 'alice@example.com');
    setInput(container.querySelector('#compose-subject')!, 'Hello');
  });
  await setBody('Test message');
  await act(async () => {
    container.querySelector('section[aria-label="New message"] form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await Promise.resolve();
  });
  await flush();
}

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  useLocaleStore.setState({ locale: 'en' });
  useSession.getState().setSession({ id: 'me', displayName: 'Me', email: 'me@example.com', groups: [], permissions: ['MAIL_READ_OWN', 'MAIL_SEND_INTERNAL'] }, false);
  localStorage.setItem(mailPreferencesKey('me', false)!, JSON.stringify({ markRead: 'manual' }));
  vi.spyOn(mailApi, 'folders').mockResolvedValue(folders);
  vi.spyOn(mailApi, 'quota').mockResolvedValue({ usedBytes: 0, quotaBytes: 0, messageCount: 0 });
  vi.spyOn(mailApi, 'messages').mockImplementation(async (folderId) => folderId === 'inbox-id' ? [message] : []);
  vi.spyOn(mailApi, 'message').mockResolvedValue({ summary: message, to: [], cc: [], bodyText: 'Full body', bodyHtml: null, attachments: [] });
  vi.spyOn(mailApi, 'flags').mockImplementation(async (_id, flags) => ({ ...message, ...flags }));
  vi.spyOn(mailApi, 'move').mockResolvedValue(moved);
  vi.spyOn(mailApi, 'send').mockResolvedValue({ messageIdHeader: '<sent@example.com>', sentMessageId: 'sent-1', localRecipients: [], externalRecipients: [] });
  // Автосохранение черновиков в этих тестах не проверяется: сеть не нужна.
  vi.spyOn(mailApi, 'createDraft').mockResolvedValue({ id: 'draft-1', revision: 1, payload: {} as never, updatedAt: '2026-01-01T00:00:00Z' });
  vi.spyOn(mailApi, 'updateDraft').mockResolvedValue({ id: 'draft-1', revision: 2, payload: {} as never, updatedAt: '2026-01-01T00:00:10Z' });
  vi.spyOn(mailApi, 'deleteDraft').mockResolvedValue();
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  vi.useRealTimers();
  localStorage.clear();
  useSession.getState().clearSession();
  vi.restoreAllMocks();
});

describe('mail mutation concurrency', () => {
  it('replies to a bare sender address and uses real To/Cc addresses for reply-all', async () => {
    const source = { ...message, sender: 'alice@example.com' };
    vi.mocked(mailApi.messages).mockResolvedValue([source]);
    vi.mocked(mailApi.message).mockResolvedValue({
      summary: source,
      to: ['me@example.com', 'Bob <bob@example.com>', 'ALICE@example.com'],
      cc: ['BOB@example.com', 'carol@example.com'],
      bodyText: 'Full body', bodyHtml: null, attachments: [],
    });
    await mount();
    await click(container.querySelector('button[aria-label="Reply all"]'));
    expect((container.querySelector('#compose-to') as HTMLInputElement).value).toBe('alice@example.com');
    expect((container.querySelector('#compose-cc') as HTMLInputElement).value).toBe('bob@example.com, carol@example.com');
  });

  it('waits for the original recipient list before enabling reply-all', async () => {
    const detail = deferred<Awaited<ReturnType<typeof mailApi.message>>>();
    vi.mocked(mailApi.message).mockReturnValue(detail.promise);
    await mount();
    const replyAll = () => container.querySelector('button[aria-label="Reply all"]') as HTMLButtonElement;
    expect(replyAll().disabled).toBe(true);
    await act(async () => { detail.resolve({ summary: message, to: ['me@example.com'], cc: [], bodyText: '', bodyHtml: null, attachments: [] }); await Promise.resolve(); });
    await flush();
    expect(replyAll().disabled).toBe(false);
  });

  it('moves the unread count and ignores a refresh captured before the move', async () => {
    await mount();
    expect(folderButton('Inbox').textContent).toBe('Inbox1');
    const staleFolders = deferred<MailFolderView[]>();
    const staleMessages = deferred<MailMessageListItem[]>();
    vi.mocked(mailApi.folders).mockReturnValue(staleFolders.promise);
    vi.mocked(mailApi.messages)
      .mockReturnValueOnce(staleMessages.promise)
      .mockImplementation(async (folderId) => folderId === 'trash-id' ? [moved] : []);
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    await click(container.querySelector('button[aria-label="Delete"]'));
    await flush();
    expect(mailApi.move).toHaveBeenCalledWith(message.id, 'trash-id');
    expect(folderButton('Inbox').textContent).toBe('Inbox');
    expect(folderButton('Trash').textContent).toBe('Trash1');
    expect(container.textContent).not.toContain(message.subject);
    await act(async () => { staleFolders.resolve(folders); staleMessages.resolve([message]); await Promise.resolve(); });
    await flush();
    expect(folderButton('Inbox').textContent).toBe('Inbox');
    expect(folderButton('Trash').textContent).toBe('Trash1');
    expect(container.textContent).not.toContain(message.subject);
  });

  it.each([true, false])('waits for a pending read write before moving (write succeeds: %s)', async (succeeds) => {
    const read = deferred<MailMessageListItem>();
    vi.mocked(mailApi.flags).mockReturnValue(read.promise);
    vi.mocked(mailApi.move).mockResolvedValue({ ...moved, seen: succeeds });
    await mount();
    await click(container.querySelector('button[aria-label="Mark read"]'));
    await click(container.querySelector('button[aria-label="Delete"]'));
    expect(mailApi.move).not.toHaveBeenCalled();
    expect((container.querySelector('button[aria-label="Mark unread"]') as HTMLButtonElement).disabled).toBe(true);
    await act(async () => {
      if (succeeds) read.resolve({ ...message, seen: true });
      else read.reject(new Error('mark read failed'));
      await Promise.resolve();
    });
    await flush();
    expect(mailApi.move).toHaveBeenCalledWith(message.id, 'trash-id');
    expect(folderButton('Inbox').textContent).toBe('Inbox');
    expect(folderButton('Trash').textContent).toBe(succeeds ? 'Trash' : 'Trash1');
  });

  it('serializes star, read and move writes for the same message', async () => {
    const star = deferred<MailMessageListItem>();
    const read = deferred<MailMessageListItem>();
    vi.mocked(mailApi.flags).mockImplementation((_id, flags) => flags.flagged ? star.promise : read.promise);
    await mount();
    await click(container.querySelector('button[aria-label="Star message"]'));
    await click(container.querySelector('button[aria-label="Mark read"]'));
    await click(container.querySelector('button[aria-label="Delete"]'));
    expect(mailApi.flags).toHaveBeenCalledTimes(1);
    expect(mailApi.move).not.toHaveBeenCalled();
    await act(async () => { star.resolve({ ...message, flagged: true }); await Promise.resolve(); });
    await flush();
    expect(mailApi.flags).toHaveBeenLastCalledWith(message.id, { seen: true });
    expect(mailApi.move).not.toHaveBeenCalled();
    await act(async () => { read.resolve({ ...message, flagged: true, seen: true }); await Promise.resolve(); });
    await flush();
    expect(mailApi.move).toHaveBeenCalledWith(message.id, 'trash-id');
  });

  it('restores a failed move without changing counts and re-enables its controls', async () => {
    const pendingMove = deferred<MailMessageListItem>();
    vi.mocked(mailApi.move).mockReturnValue(pendingMove.promise);
    await mount();
    await click(container.querySelector('button[aria-label="Delete"]'));
    expect((container.querySelector('button[aria-label="Delete"]') as HTMLButtonElement).disabled).toBe(true);
    await act(async () => { pendingMove.reject(new Error('move failed')); await Promise.resolve(); });
    await flush();
    expect(folderButton('Inbox').textContent).toBe('Inbox1');
    expect(folderButton('Trash').textContent).toBe('Trash');
    expect(container.querySelector('article h1')?.textContent).toBe(message.subject);
    expect((container.querySelector('button[aria-label="Delete"]') as HTMLButtonElement).disabled).toBe(false);
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('move failed');
  });

  it('keeps an optimistic star through a refresh and rolls it back if the write fails', async () => {
    const star = deferred<MailMessageListItem>();
    vi.mocked(mailApi.flags).mockReturnValue(star.promise);
    await mount();
    await click(container.querySelector('button[aria-label="Star message"]'));
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    await flush();
    expect(container.querySelector('button[aria-label="Remove star"]')).not.toBeNull();
    await act(async () => { star.reject(new Error('star failed')); await Promise.resolve(); });
    await flush();
    expect(container.querySelector('button[aria-label="Star message"]')).not.toBeNull();
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('star failed');
  });

  it('rolls a failed queued star change back to the last confirmed value', async () => {
    const first = deferred<MailMessageListItem>();
    const second = deferred<MailMessageListItem>();
    vi.mocked(mailApi.flags).mockImplementation((_id, flags) => flags.flagged ? first.promise : second.promise);
    await mount();
    await click(container.querySelector('button[aria-label="Star message"]'));
    await click(container.querySelector('button[aria-label="Remove star"]'));
    expect(mailApi.flags).toHaveBeenCalledTimes(1);
    await act(async () => { first.resolve({ ...message, flagged: true }); await Promise.resolve(); });
    await flush();
    expect(mailApi.flags).toHaveBeenLastCalledWith(message.id, { flagged: false });
    await act(async () => { second.reject(new Error('unstar failed')); await Promise.resolve(); });
    await flush();
    expect(container.querySelector('button[aria-label="Remove star"]')).not.toBeNull();
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('unstar failed');
  });

  it('does not change the selected message when a move finishes after the user selected another', async () => {
    const first = { ...message, subject: 'First message', date: '2026-04-03T10:00:00Z', seen: true };
    const second = { ...first, id: 'message-2', subject: 'Second message', date: '2026-04-02T10:00:00Z' };
    const third = { ...first, id: 'message-3', subject: 'Third message', date: '2026-04-01T10:00:00Z' };
    const pendingMove = deferred<MailMessageListItem>();
    vi.mocked(mailApi.messages).mockResolvedValue([first, second, third]);
    vi.mocked(mailApi.move).mockReturnValue(pendingMove.promise);
    await mount();
    expect(container.querySelector('article h1')?.textContent).toBe('First message');
    await click(container.querySelector('button[aria-label="Delete"]'));
    const thirdRow = [...container.querySelectorAll('[draggable="true"]')].find((row) => row.textContent?.includes('Third message'))!;
    await click(thirdRow);
    expect(container.querySelector('article h1')?.textContent).toBe('Third message');
    await act(async () => { pendingMove.resolve({ ...first, folderId: 'trash-id' }); await Promise.resolve(); });
    await flush();
    expect(container.querySelector('article h1')?.textContent).toBe('Third message');
  });

  it('keeps the newer read state and unread count when a sent-folder refresh finishes late', async () => {
    const sentMessage = { ...message, folderId: 'sent-id', seen: true, subject: 'Already sent' };
    const oldSentList = deferred<MailMessageListItem[]>();
    const readChange = deferred<MailMessageListItem>();
    vi.mocked(mailApi.messages).mockImplementation(async (folderId) => folderId === 'sent-id' ? [sentMessage] : []);
    await mount();
    await click(folderButton('Sent'));
    vi.mocked(mailApi.messages).mockReturnValue(oldSentList.promise);
    vi.mocked(mailApi.flags).mockReturnValue(readChange.promise);
    await sendDraft();
    await flush();
    await click(container.querySelector('button[aria-label="Mark unread"]'));
    await act(async () => { readChange.resolve({ ...sentMessage, seen: false }); await Promise.resolve(); });
    await act(async () => { oldSentList.resolve([sentMessage]); await Promise.resolve(); });
    await flush();
    expect(folderButton('Sent').textContent).toBe('Sent1');
    expect(container.querySelector('button[aria-label="Mark read"]')).not.toBeNull();
  });

  it('does not let an older initial list overwrite a newer refresh', async () => {
    const oldList = deferred<MailMessageListItem[]>();
    const fresh = { ...message, id: 'new-message', subject: 'Newer snapshot' };
    vi.mocked(mailApi.messages).mockReturnValueOnce(oldList.promise).mockResolvedValue([fresh]);
    await mount();
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    await flush();
    expect(container.textContent).toContain('Newer snapshot');
    await act(async () => { oldList.resolve([message]); await Promise.resolve(); });
    await flush();
    expect(container.textContent).toContain('Newer snapshot');
    expect(container.textContent).not.toContain(message.subject);
  });

  it('does not resurrect a moved message when the sent-list refresh returns late', async () => {
    const sentMessage = { ...message, folderId: 'sent-id', subject: 'Move from Sent' };
    const sentFolders = folders.map((item) => item.id === 'inbox-id' ? { ...item, unread: 0 } : item.id === 'sent-id' ? { ...item, unread: 1 } : item);
    const staleSentList = deferred<MailMessageListItem[]>();
    vi.mocked(mailApi.folders).mockResolvedValue(sentFolders);
    vi.mocked(mailApi.messages).mockImplementation(async (folderId) => folderId === 'sent-id' ? [sentMessage] : []);
    vi.mocked(mailApi.move).mockResolvedValue({ ...sentMessage, folderId: 'trash-id' });
    await mount();
    await click(folderButton('Sent'));
    vi.mocked(mailApi.messages).mockReturnValueOnce(staleSentList.promise).mockResolvedValue([]);
    await sendDraft();
    await click(container.querySelector('button[aria-label="Delete"]'));
    await flush();
    expect(folderButton('Sent').textContent).toBe('Sent');
    expect(folderButton('Trash').textContent).toBe('Trash1');
    await act(async () => { staleSentList.resolve([sentMessage]); await Promise.resolve(); });
    await flush();
    expect(container.textContent).not.toContain('Move from Sent');
    expect(folderButton('Sent').textContent).toBe('Sent');
    expect(folderButton('Trash').textContent).toBe('Trash1');
  });

  it('shows sent immediately and keeps newer counts when its old folder snapshot returns late', async () => {
    const oldFolders = deferred<MailFolderView[]>();
    const latest = folders.map((item) => item.id === 'inbox-id' ? { ...item, unread: 2 } : item);
    await mount();
    vi.mocked(mailApi.folders).mockReturnValueOnce(oldFolders.promise).mockResolvedValue(latest);
    await sendDraft();
    expect(container.querySelector('section[aria-label="New message"]')?.textContent).toContain('Message sent');
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    await flush();
    expect(folderButton('Inbox').textContent).toBe('Inbox2');
    await act(async () => { oldFolders.resolve(folders); await Promise.resolve(); });
    await flush();
    expect(folderButton('Inbox').textContent).toBe('Inbox2');
  });

  it('keeps a newly created folder when an older post-send folder list returns', async () => {
    useSession.getState().setSession({ id: 'me', displayName: 'Me', email: 'me@example.com', groups: [], permissions: ['MAIL_READ_OWN', 'MAIL_SEND_INTERNAL', 'MAIL_MANAGE_FOLDERS'] }, false);
    const oldFolders = deferred<MailFolderView[]>();
    const created: MailFolderView = { id: 'new-folder', name: 'Receipts', specialUse: null, total: 0, unread: 0 };
    vi.spyOn(mailApi, 'createFolder').mockResolvedValue(created);
    await mount();
    vi.mocked(mailApi.folders).mockReturnValue(oldFolders.promise);
    await sendDraft();
    const createButton = [...container.querySelectorAll('button')].find((button) => button.textContent?.trim() === 'Create folder');
    await click(createButton);
    const dialog = container.querySelector('[aria-labelledby="create-folder-title"]')!;
    await act(async () => setInput(dialog.querySelector('#new-folder-name')!, 'Receipts'));
    await act(async () => { dialog.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await Promise.resolve(); });
    await flush();
    expect(folderButton('Receipts')).toBeTruthy();
    await act(async () => { oldFolders.resolve(folders); await Promise.resolve(); });
    await flush();
    expect(folderButton('Receipts')).toBeTruthy();
  });

  it('retries the initial folder load if a send supersedes its snapshot', async () => {
    const initialFolders = deferred<MailFolderView[]>();
    const sendFolders = deferred<MailFolderView[]>();
    vi.mocked(mailApi.folders)
      .mockReturnValueOnce(initialFolders.promise)
      .mockReturnValueOnce(sendFolders.promise)
      .mockResolvedValue(folders);
    await mount();
    await sendDraft();
    await act(async () => { initialFolders.resolve(folders); await Promise.resolve(); });
    await flush();
    expect(folderButton('Inbox')).toBeTruthy();
    await act(async () => { sendFolders.resolve(folders); await Promise.resolve(); });
    await flush();
    expect(folderButton('Inbox')).toBeTruthy();
    expect(folderButton('Inbox').textContent).toBe('Inbox1');
  });

  it('refreshes Sent if the user opens it while a send is in progress', async () => {
    const pendingSend = deferred<Awaited<ReturnType<typeof mailApi.send>>>();
    const sent = { ...message, id: 'new-sent', folderId: 'sent-id', seen: true, subject: 'Just sent' };
    vi.mocked(mailApi.send).mockReturnValue(pendingSend.promise);
    await mount();
    await sendDraft();
    await click(folderButton('Sent'));
    vi.mocked(mailApi.messages).mockImplementation(async (folderId) => folderId === 'sent-id' ? [sent] : [message]);
    await act(async () => { pendingSend.resolve({ messageIdHeader: 'id', sentMessageId: sent.id, localRecipients: [], externalRecipients: [] }); await Promise.resolve(); });
    await flush();
    expect(container.textContent).toContain('Just sent');
  });

  it('reports a successful send even when the subsequent folder refresh fails', async () => {
    await mount();
    vi.mocked(mailApi.folders).mockRejectedValue(new Error('refresh failed'));
    await sendDraft();
    expect(mailApi.send).toHaveBeenCalledTimes(1);
    expect(container.querySelector('section[aria-label="New message"]')?.textContent).toContain('Message sent');
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('Message sent, but');
  });

  it.each([new Error('network unavailable'), new ApiError(500, 'server error'), new ApiError(408, 'timeout')])(
    'warns that a send may already be queued after an ambiguous failure: %s', async (error) => {
      vi.mocked(mailApi.send).mockRejectedValue(error);
      await mount();
      await sendDraft();
      const alert = container.querySelector('section[aria-label="New message"] [role="alert"]');
      expect(alert?.textContent).toContain('The message may already be queued');
      expect(alert?.textContent).toContain(error.message);
      expect(mailApi.send).toHaveBeenCalledTimes(1);
    },
  );

  it('shows a definite validation rejection and permits a deliberate retry', async () => {
    vi.mocked(mailApi.send).mockRejectedValueOnce(new ApiError(422, 'Invalid recipient'))
      .mockResolvedValueOnce({ messageIdHeader: 'id', sentMessageId: 'sent-1', localRecipients: [], externalRecipients: [] });
    await mount();
    await sendDraft();
    const composer = container.querySelector('section[aria-label="New message"]')!;
    expect(composer.querySelector('[role="alert"]')?.textContent).toBe('Invalid recipient');
    await act(async () => { composer.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await Promise.resolve(); });
    await flush();
    expect(mailApi.send).toHaveBeenCalledTimes(2);
    expect(composer.textContent).toContain('Message sent');
  });

  it('warns without offering a resend when the server could not save the sent copy', async () => {
    vi.mocked(mailApi.send).mockResolvedValue({ messageIdHeader: 'id', sentMessageId: null, localRecipients: [], externalRecipients: [] });
    await mount();
    await sendDraft();
    const composer = container.querySelector('section[aria-label="New message"]')!;
    expect(composer.textContent).toContain('Message sent');
    expect(composer.querySelector('[role="alert"]')?.textContent).toContain('A copy could not be saved in Sent');
    expect(composer.querySelector('form')).toBeNull();
    vi.useFakeTimers();
    await act(async () => { vi.advanceTimersByTime(1200); });
    expect(container.contains(composer)).toBe(true);
    expect(mailApi.send).toHaveBeenCalledTimes(1);
  });

  it('prevents duplicate submissions while the first send is pending', async () => {
    const pendingSend = deferred<Awaited<ReturnType<typeof mailApi.send>>>();
    vi.mocked(mailApi.send).mockReturnValue(pendingSend.promise);
    await mount();
    await click([...container.querySelectorAll('button')].find((button) => button.textContent?.trim() === 'Compose'));
    await act(async () => {
      setInput(container.querySelector('#compose-to')!, 'alice@example.com');
      setInput(container.querySelector('#compose-subject')!, 'Hello');
    });
    await setBody('Test message');
    const form = container.querySelector('section[aria-label="New message"] form')!;
    await act(async () => {
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      await Promise.resolve();
    });
    await flush();
    expect(mailApi.send).toHaveBeenCalledTimes(1);
    await act(async () => { pendingSend.resolve({ messageIdHeader: 'id', sentMessageId: null, localRecipients: [], externalRecipients: [] }); await Promise.resolve(); });
  });

  it('does not close a newer draft when an older draft finishes sending after being closed', async () => {
    const pendingSend = deferred<Awaited<ReturnType<typeof mailApi.send>>>();
    vi.mocked(mailApi.send).mockReturnValue(pendingSend.promise);
    await mount();
    await sendDraft();
    await click(container.querySelector('button[aria-label="Close draft"]'));
    await click([...container.querySelectorAll('button')].find((button) => button.textContent?.trim() === 'Compose'));
    expect(container.querySelector('section[aria-label="New message"] form')).not.toBeNull();
    vi.useFakeTimers();
    await act(async () => { pendingSend.resolve({ messageIdHeader: 'id', sentMessageId: null, localRecipients: [], externalRecipients: [] }); await Promise.resolve(); });
    await flush();
    await act(async () => { vi.advanceTimersByTime(1000); await Promise.resolve(); });
    expect(container.querySelector('section[aria-label="New message"] form')).not.toBeNull();
  });
});
