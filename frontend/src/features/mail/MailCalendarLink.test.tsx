// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mailApi, type MailFolderView, type MailMessageDetail } from '../../api/mailApi';
import { SidebarSlotContext } from '../../components/SidebarSlot';
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

function CurrentLocation() {
  const location = useLocation();
  return <output data-testid="location">{location.search}</output>;
}

const folders: MailFolderView[] = [
  { id: 'inbox-id', name: 'Inbox', specialUse: 'INBOX', total: 0, unread: 0 },
  { id: 'archive-id', name: 'Archive', specialUse: 'ARCHIVE', total: 100, unread: 0 },
];
const target: MailMessageDetail = {
  summary: {
    id: 'linked', folderId: 'archive-id', uid: 1, sender: 'Alice <alice@example.com>', subject: 'Linked source email',
    snippet: 'Preview', date: '2026-01-01T10:00:00Z', sizeBytes: 200, seen: true, flagged: false,
    answered: false, hasAttachments: false, avStatus: 'CLEAN', spamScore: null,
  },
  to: ['me@example.com'], cc: [], bodyText: 'Full source body', bodyHtml: null, attachments: [],
};

let container: HTMLDivElement;
let root: Root;

async function flush() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
}

async function renderAt(path: string) {
  await act(async () => root.render(<MemoryRouter initialEntries={[path]}><CurrentLocation /><MailPage /></MemoryRouter>));
  await flush();
}

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  useLocaleStore.setState({ locale: 'en' });
  useSession.getState().setSession({ id: 'me', displayName: 'Me', email: 'me@example.com', groups: [], permissions: ['MAIL_READ_OWN'] }, false);
  vi.spyOn(mailApi, 'quota').mockResolvedValue({ usedBytes: 0, quotaBytes: 0, messageCount: 0 });
  vi.spyOn(mailApi, 'messages').mockResolvedValue([]);
  vi.spyOn(mailApi, 'message').mockResolvedValue(target);
  vi.spyOn(mailApi, 'flags').mockResolvedValue(target.summary);
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(async () => {
  vi.useRealTimers();
  await act(async () => root.unmount());
  container.remove();
  localStorage.clear();
  useSession.getState().clearSession();
  vi.restoreAllMocks();
});

describe('calendar source-email links', () => {
  it('waits for folder metadata and opens an older message outside the first page', async () => {
    const pendingFolders = deferred<MailFolderView[]>();
    vi.spyOn(mailApi, 'folders').mockReturnValue(pendingFolders.promise);
    await renderAt('/mail?mailId=linked');
    expect(mailApi.message).not.toHaveBeenCalled();
    expect(container.querySelector('[data-testid="location"]')?.textContent).toContain('mailId=linked');

    await act(async () => { pendingFolders.resolve(folders); await Promise.resolve(); });
    await flush();
    await flush();
    expect(mailApi.message).toHaveBeenCalledWith('linked');
    expect(mailApi.messages).toHaveBeenCalledWith('archive-id');
    expect(container.textContent).toContain('Linked source email');
    expect(container.textContent).toContain('Full source body');
    expect(container.querySelector('[data-testid="location"]')?.textContent).not.toContain('mailId');
  });

  it('finishes with a clear error when the server returns no folders', async () => {
    vi.spyOn(mailApi, 'folders').mockResolvedValue([]);
    await renderAt('/mail?mailId=linked');
    await flush();
    expect(mailApi.message).not.toHaveBeenCalled();
    expect(container.textContent).toContain('No mail folders found.');
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('mail folders are unavailable');
    expect(container.querySelector('[data-testid="location"]')?.textContent).not.toContain('mailId');
  });

  it('finishes with a clear error when loading folders fails', async () => {
    vi.spyOn(mailApi, 'folders').mockRejectedValue(new Error('network failed'));
    await renderAt('/mail?mailId=linked');
    await flush();
    expect(mailApi.message).not.toHaveBeenCalled();
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('mail folders are unavailable');
    expect(container.querySelector('[data-testid="location"]')?.textContent).not.toContain('mailId');
  });

  it('consumes compose and source-email commands without restoring either URL parameter', async () => {
    useSession.getState().setSession({ id: 'demo', displayName: 'Demo', email: 'demo@example.com', groups: [], permissions: ['MAIL_READ_OWN'] }, true);
    await renderAt('/mail?compose=1&mailId=m5');
    await flush();
    expect(container.querySelector('[aria-label="New message"]')).not.toBeNull();
    expect(container.textContent).toContain('50-mailbox pilot');
    expect(container.querySelector('[data-testid="location"]')?.textContent).toBe('');
  });

  it('retains linked-message flag and seen changes when an older message is absent from refresh results', async () => {
    vi.spyOn(mailApi, 'folders').mockResolvedValue(folders);
    await renderAt('/mail?mailId=linked');
    await flush();
    const flag = container.querySelector('button[aria-label="Star message"]') as HTMLButtonElement;
    const unread = container.querySelector('button[aria-label="Mark unread"]') as HTMLButtonElement;
    expect(flag).not.toBeNull();
    expect(unread).not.toBeNull();
    await act(async () => { flag.click(); unread.click(); });
    expect(mailApi.flags).toHaveBeenCalledWith('linked', { flagged: true });
    expect(mailApi.flags).toHaveBeenCalledWith('linked', { seen: false });
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
    await flush();
    expect(container.querySelector('button[aria-label="Remove star"]')).not.toBeNull();
    expect(container.querySelector('button[aria-label="Mark read"]')).not.toBeNull();
    expect(container.textContent).toContain('Linked source email');
  });

  it('sends an explicit unread change after a pending automatic read completes', async () => {
    const first = deferred<typeof target.summary>();
    const unreadTarget = { ...target, summary: { ...target.summary, seen: false } };
    vi.spyOn(mailApi, 'folders').mockResolvedValue(folders);
    vi.mocked(mailApi.message).mockResolvedValue(unreadTarget);
    vi.mocked(mailApi.flags).mockImplementation((_id, flags) => flags.seen === true ? first.promise : Promise.resolve(unreadTarget.summary));
    await renderAt('/mail?mailId=linked');
    await flush();
    expect(mailApi.flags).toHaveBeenCalledWith('linked', { seen: true });
    await act(async () => { (container.querySelector('button[aria-label="Mark unread"]') as HTMLButtonElement).click(); });
    expect(vi.mocked(mailApi.flags).mock.calls.filter(([, value]) => value.seen === false)).toHaveLength(0);
    await act(async () => { first.resolve(target.summary); await Promise.resolve(); await Promise.resolve(); });
    await flush();
    expect(mailApi.flags).toHaveBeenLastCalledWith('linked', { seen: false });
    expect(container.querySelector('button[aria-label="Mark read"]')).not.toBeNull();
  });

  it('restores an unread message if automatic marking fails, without an endless retry', async () => {
    const unreadTarget = { ...target, summary: { ...target.summary, seen: false } };
    vi.spyOn(mailApi, 'folders').mockResolvedValue(folders);
    vi.mocked(mailApi.message).mockResolvedValue(unreadTarget);
    vi.mocked(mailApi.flags).mockRejectedValue(new Error('mark failed'));
    await renderAt('/mail?mailId=linked');
    await flush();
    expect(mailApi.flags).toHaveBeenCalledTimes(1);
    expect(container.querySelector('button[aria-label="Mark read"]')).not.toBeNull();
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('mark failed');
  });

  it('rolls back a failed manual unread change to the last confirmed value', async () => {
    localStorage.setItem(mailPreferencesKey('me', false)!, JSON.stringify({ markRead: 'manual' }));
    vi.spyOn(mailApi, 'folders').mockResolvedValue(folders);
    vi.mocked(mailApi.flags).mockRejectedValue(new Error('mark failed'));
    await renderAt('/mail?mailId=linked');
    await flush();
    await act(async () => { (container.querySelector('button[aria-label="Mark unread"]') as HTMLButtonElement).click(); });
    await flush();
    expect(mailApi.flags).toHaveBeenCalledWith('linked', { seen: false });
    expect(container.querySelector('button[aria-label="Mark unread"]')).not.toBeNull();
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('mark failed');
  });

  it('preserves the optimistic read state and unread count during a refresh', async () => {
    const first = deferred<typeof target.summary>();
    const unreadTarget = { ...target, summary: { ...target.summary, seen: false } };
    const unreadFolders = folders.map((item) => item.id === 'archive-id' ? { ...item, unread: 1 } : item);
    vi.spyOn(mailApi, 'folders').mockResolvedValue(unreadFolders);
    vi.mocked(mailApi.message).mockResolvedValue(unreadTarget);
    vi.mocked(mailApi.messages).mockImplementation(async (folderId) => folderId === 'archive-id' ? [unreadTarget.summary] : []);
    vi.mocked(mailApi.flags).mockReturnValue(first.promise);
    await renderAt('/mail?mailId=linked');
    await flush();
    const archive = () => [...container.querySelectorAll('nav[aria-label="Mail folders"] button')]
      .find((item) => item.textContent?.startsWith('Archive'))!;
    expect(archive().textContent).toBe('Archive');
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    await flush();
    expect(container.querySelector('button[aria-label="Mark unread"]')).not.toBeNull();
    expect(archive().textContent).toBe('Archive');
    await act(async () => { first.resolve(target.summary); await Promise.resolve(); });
  });

  it('ignores a stale refresh that finishes after the read write succeeds', async () => {
    const first = deferred<typeof target.summary>();
    const staleList = deferred<(typeof target.summary)[]>();
    const unreadTarget = { ...target, summary: { ...target.summary, seen: false } };
    vi.spyOn(mailApi, 'folders').mockResolvedValue(folders);
    vi.mocked(mailApi.message).mockResolvedValue(unreadTarget);
    vi.mocked(mailApi.flags).mockReturnValue(first.promise);
    await renderAt('/mail?mailId=linked');
    await flush();
    vi.mocked(mailApi.messages).mockReturnValue(staleList.promise);
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    await act(async () => { first.resolve(target.summary); await Promise.resolve(); });
    await act(async () => { staleList.resolve([unreadTarget.summary]); await Promise.resolve(); });
    await flush();
    expect(container.querySelector('button[aria-label="Mark unread"]')).not.toBeNull();
    expect(container.querySelector('button[aria-label="Mark read"]')).toBeNull();
  });

  it('rolls a failed queued change back to the preceding confirmed change', async () => {
    const first = deferred<typeof target.summary>();
    const second = deferred<typeof target.summary>();
    const unreadTarget = { ...target, summary: { ...target.summary, seen: false } };
    vi.spyOn(mailApi, 'folders').mockResolvedValue(folders);
    vi.mocked(mailApi.message).mockResolvedValue(unreadTarget);
    vi.mocked(mailApi.flags).mockImplementation((_id, flags) => flags.seen ? first.promise : second.promise);
    await renderAt('/mail?mailId=linked');
    await flush();
    await act(async () => { (container.querySelector('button[aria-label="Mark unread"]') as HTMLButtonElement).click(); });
    await act(async () => { first.resolve(target.summary); await Promise.resolve(); });
    await act(async () => { second.reject(new Error('unread failed')); await Promise.resolve(); });
    await flush();
    expect(container.querySelector('button[aria-label="Mark unread"]')).not.toBeNull();
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('unread failed');
  });

  it('does not resurrect a linked message in its old folder after moving it', async () => {
    const trash: MailFolderView = { id: 'trash-id', name: 'Trash', specialUse: 'TRASH', total: 0, unread: 0 };
    const moved = { ...target.summary, folderId: trash.id };
    vi.spyOn(mailApi, 'folders').mockResolvedValue([...folders, trash]);
    vi.mocked(mailApi.messages).mockImplementation(async (folderId) => folderId === trash.id ? [moved] : []);
    const move = vi.spyOn(mailApi, 'move').mockResolvedValue(moved);
    await renderAt('/mail?mailId=linked');
    await flush();
    const remove = container.querySelector('button[aria-label="Delete"]') as HTMLButtonElement;
    expect(remove).not.toBeNull();
    await act(async () => { remove.click(); await Promise.resolve(); });
    await flush();
    expect(move).toHaveBeenCalledWith('linked', 'trash-id');
    expect(container.textContent).not.toContain('Linked source email');
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
    await flush();
    expect(container.textContent).not.toContain('Linked source email');
    const nav = container.querySelector('nav[aria-label="Mail folders"]')!;
    const trashButton = [...nav.querySelectorAll('button')].find((item) => item.textContent?.includes('Trash'))!;
    await act(async () => { trashButton.click(); await Promise.resolve(); });
    await flush();
    expect(container.textContent).toContain('Linked source email');
  });

  it('opens the widget after the native drag starts and cancels the timer if dragging ends', async () => {
    useSession.getState().setSession({ id: 'demo', displayName: 'Demo', email: 'demo@example.com', groups: [], permissions: ['MAIL_READ_OWN'] }, true);
    const openWidget = vi.fn();
    const context = { inAppShell: false, collapsed: false, slotEl: null, mobileSlotEl: null, openPalette: vi.fn(), onOpenCalendarWidget: openWidget };
    await act(async () => root.render(<MemoryRouter><SidebarSlotContext.Provider value={context}><MailPage /></SidebarSlotContext.Provider></MemoryRouter>));
    await flush();
    vi.useFakeTimers();
    const row = container.querySelector('[draggable="true"]')!;
    const setData = vi.fn();
    const start = () => {
      const event = new Event('dragstart', { bubbles: true, cancelable: true });
      Object.defineProperty(event, 'dataTransfer', { value: { setData, effectAllowed: 'none' } });
      row.dispatchEvent(event);
    };
    await act(async () => start());
    expect(setData).toHaveBeenCalledWith('application/x-corvex-mail', expect.any(String));
    expect(openWidget).not.toHaveBeenCalled();
    await act(async () => { vi.advanceTimersByTime(100); });
    expect(openWidget).toHaveBeenCalledTimes(1);
    await act(async () => { start(); row.dispatchEvent(new Event('dragend', { bubbles: true })); vi.advanceTimersByTime(100); });
    expect(openWidget).toHaveBeenCalledTimes(1);
  });
});
