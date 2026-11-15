// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/httpClient';
import { mailApi, type MailFolderView } from '../../api/mailApi';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import { DEFAULT_MAIL_PREFERENCES, mailPreferencesKey } from './mailPreferences';
import MailPage from './MailPage';

let container: HTMLDivElement;
let root: Root;

async function flush() { await act(async () => { await Promise.resolve(); await Promise.resolve(); }); }
async function click(element: Element | null | undefined) {
  expect(element).toBeTruthy();
  await act(async () => { (element as HTMLElement).click(); await Promise.resolve(); });
}
function inputValue(element: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement, value: string) {
  const proto = element instanceof HTMLSelectElement ? HTMLSelectElement.prototype : element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(element, value);
  element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
}
function buttonWithText(text: string, within: ParentNode = container) {
  return [...within.querySelectorAll('button')].find((button) => button.textContent?.trim() === text);
}
function checkbox(label: string, within: ParentNode = container) {
  const node = [...within.querySelectorAll('label')].find((item) => item.textContent?.includes(label));
  return node?.querySelector('input[type="checkbox"]') as HTMLInputElement | undefined;
}
async function mount(path = '/mail') {
  await act(async () => root.render(<MemoryRouter initialEntries={[path]}><MailPage /></MemoryRouter>));
  await flush();
}
function demo(permissions = ['MAIL_READ_OWN', 'MAIL_MANAGE_FOLDERS']) {
  useSession.getState().setSession({ id: 'demo-user', displayName: 'Demo', email: 'demo@example.com', groups: [], permissions }, true);
}

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  useLocaleStore.setState({ locale: 'en' });
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

describe('mail folders', () => {
  it('creates a demo folder and moves a message into it', async () => {
    demo();
    await mount();
    await click(buttonWithText('Create folder'));
    const dialog = container.querySelector('[role="dialog"][aria-labelledby="create-folder-title"]')!;
    await act(async () => inputValue(dialog.querySelector('#new-folder-name') as HTMLInputElement, '  Projects  '));
    await act(async () => { dialog.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await Promise.resolve(); });
    expect(container.querySelector('[role="dialog"][aria-labelledby="create-folder-title"]')).toBeNull();
    const nav = container.querySelector('nav[aria-label="Mail folders"]')!;
    const projects = buttonWithText('Projects', nav)!;
    expect(projects).toBeTruthy();
    await click([...nav.querySelectorAll('button')].find((item) => item.querySelector('span')?.textContent === 'Inbox'));
    const select = container.querySelector('select[aria-label="Move to folder"]') as HTMLSelectElement;
    const destination = [...select.options].find((option) => option.textContent === 'Projects')!;
    await act(async () => inputValue(select, destination.value));
    await click(projects);
    expect(container.textContent).toContain('October payment summary');
  });

  it('explains when the sanitized storage name is too long', async () => {
    demo();
    await mount();
    await click(buttonWithText('Create folder'));
    const dialog = container.querySelector('[aria-labelledby="create-folder-title"]')!;
    await act(async () => inputValue(dialog.querySelector('#new-folder-name') as HTMLInputElement, 'x'.repeat(256)));
    await act(async () => dialog.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
    expect(dialog.querySelector('[role="alert"]')?.textContent).toBe('The name is too long for the file storage.');
    expect(container.contains(dialog)).toBe(true);
  });

  it('requires the folder permission and creates a real folder through the API', async () => {
    demo(['MAIL_READ_OWN']);
    await mount();
    expect((buttonWithText('Create folder') as HTMLButtonElement).disabled).toBe(true);
    await act(async () => root.unmount());
    root = createRoot(container);
    useSession.getState().setSession({ id: 'user', displayName: 'User', email: 'user@example.com', groups: [], permissions: ['MAIL_READ_OWN', 'MAIL_MANAGE_FOLDERS'] }, false);
    const inbox: MailFolderView = { id: 'inbox', name: 'Inbox', specialUse: 'INBOX', total: 0, unread: 0 };
    const created: MailFolderView = { id: 'folder-new', name: 'Receipts', specialUse: null, total: 0, unread: 0 };
    vi.spyOn(mailApi, 'folders').mockResolvedValue([inbox]);
    vi.spyOn(mailApi, 'messages').mockResolvedValue([]);
    vi.spyOn(mailApi, 'quota').mockResolvedValue({ usedBytes: 0, quotaBytes: 0, messageCount: 0 });
    const create = vi.spyOn(mailApi, 'createFolder').mockResolvedValue(created);
    await mount();
    await click(buttonWithText('Create folder'));
    const dialog = container.querySelector('[aria-labelledby="create-folder-title"]')!;
    await act(async () => inputValue(dialog.querySelector('#new-folder-name') as HTMLInputElement, ' Receipts '));
    await act(async () => { dialog.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await Promise.resolve(); });
    await flush();
    expect(create).toHaveBeenCalledWith('Receipts');
    expect(buttonWithText('Receipts', container.querySelector('nav[aria-label="Mail folders"]')!)).toBeTruthy();
    expect(mailApi.messages).toHaveBeenCalledWith('folder-new');
  });

  it('shows a duplicate error without closing the dialog', async () => {
    useSession.getState().setSession({ id: 'user', displayName: 'User', email: 'user@example.com', groups: [], permissions: ['MAIL_READ_OWN', 'MAIL_MANAGE_FOLDERS'] }, false);
    vi.spyOn(mailApi, 'folders').mockResolvedValue([{ id: 'inbox', name: 'Inbox', specialUse: 'INBOX', total: 0, unread: 0 }]);
    vi.spyOn(mailApi, 'messages').mockResolvedValue([]);
    vi.spyOn(mailApi, 'quota').mockResolvedValue({ usedBytes: 0, quotaBytes: 0, messageCount: 0 });
    vi.spyOn(mailApi, 'createFolder').mockRejectedValue(new ApiError(409, 'duplicate'));
    await mount();
    await click(buttonWithText('Create folder'));
    const dialog = container.querySelector('[aria-labelledby="create-folder-title"]')!;
    await act(async () => inputValue(dialog.querySelector('#new-folder-name') as HTMLInputElement, 'Taken'));
    await act(async () => { dialog.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await Promise.resolve(); });
    expect(dialog.querySelector('[role="alert"]')?.textContent).toBe('The name is taken or conflicts with another folder in storage.');
    expect(container.contains(dialog)).toBe(true);
  });
});

describe('mail settings', () => {
  it('saves browser preferences, updates the list and inserts a signature into new mail and replies', async () => {
    demo();
    await mount('/mail?settings=1');
    const settings = container.querySelector('[aria-labelledby="mail-settings-title"]')!;
    expect(settings).toBeTruthy();
    await act(async () => {
      inputValue(settings.querySelector('#mail-density') as HTMLSelectElement, 'compact');
      inputValue(settings.querySelector('#mail-default-sort') as HTMLSelectElement, 'date-asc');
      inputValue(settings.querySelector('#mail-signature') as HTMLTextAreaElement, 'Best, Alice');
    });
    await click(checkbox('Show preview text', settings));
    await click(checkbox('Add to replies and forwards', settings));
    await act(async () => settings.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
    expect(container.querySelector('[aria-labelledby="mail-settings-title"]')).toBeNull();
    const saved = JSON.parse(localStorage.getItem(mailPreferencesKey('demo-user', true)!)!);
    expect(saved).toMatchObject({ density: 'compact', defaultSort: 'date-asc', signature: 'Best, Alice', showPreview: false, signatureForReplies: true });
    const firstRow = container.querySelector('[draggable="true"]')!;
    expect(firstRow.className).toContain('py-1.5');
    expect(firstRow.querySelector('p')).toBeNull();
    await click([...container.querySelectorAll('button')].find((item) => item.textContent?.includes('Compose')));
    expect(container.querySelector('[aria-label="Message body"]')?.textContent).toContain('Best, Alice');
    await click(container.querySelector('button[aria-label="Close draft"]'));
    await click(container.querySelector('button[aria-label="Reply"]'));
    expect(container.querySelector('[aria-label="Message body"]')?.textContent).toContain('Best, Alice');
  });

  it('preserves explicit unread state for an originally read demo message after reopening Mail', async () => {
    demo();
    localStorage.setItem(mailPreferencesKey('demo-user', true)!, JSON.stringify({ ...DEFAULT_MAIL_PREFERENCES, markRead: 'manual' }));
    await mount();
    const row = () => [...container.querySelectorAll('[draggable="true"]')].find((item) => item.textContent?.includes('Supply agreement'))!;
    await click(row());
    await click(container.querySelector('button[aria-label="Mark unread"]'));
    expect(useSession.getState().demoUnreadMailIds).toContain('m3');
    expect(row().querySelector('[aria-label="Unread"]')).not.toBeNull();
    await act(async () => root.unmount());
    root = createRoot(container);
    await mount();
    expect(row().querySelector('[aria-label="Unread"]')).not.toBeNull();
    await click(row());
    await click(container.querySelector('button[aria-label="Mark read"]'));
    expect(useSession.getState().demoUnreadMailIds).not.toContain('m3');
    expect(row().querySelector('[aria-label="Unread"]')).toBeNull();
  });

  it('does not automatically mark a message read when manual mode is selected', async () => {
    demo();
    localStorage.setItem(mailPreferencesKey('demo-user', true)!, JSON.stringify({ ...DEFAULT_MAIL_PREFERENCES, markRead: 'manual' }));
    await mount();
    expect(useSession.getState().demoReadMailIds).not.toContain('m1');
    await click(container.querySelector('[draggable="true"]'));
    expect(useSession.getState().demoReadMailIds).not.toContain('m1');
    await click(container.querySelector('button[aria-label="Mark read"]'));
    expect(useSession.getState().demoReadMailIds).toContain('m1');
  });
});
