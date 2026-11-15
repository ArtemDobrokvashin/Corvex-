// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mailApi, type MailMessageListItem } from '../../api/mailApi';
import { useLocaleStore } from '../../i18n';
import MailSettingsModal from './MailSettingsModal';
import { DEFAULT_MAIL_PREFERENCES } from './mailPreferences';
import { useMailNotifications } from './useMailNotifications';

function message(uid: number, seen = false): MailMessageListItem {
  return { id: `m${uid}`, folderId: 'inbox', uid, sender: 'person@example.com', subject: 'Subject', snippet: null, date: '2026-01-01T00:00:00Z', sizeBytes: 1, seen, flagged: false, answered: false, hasAttachments: false, avStatus: 'CLEAN', spamScore: null };
}
const instances: MockNotification[] = [];
class MockNotification {
  static permission: NotificationPermission = 'granted';
  static requestPermission = vi.fn<() => Promise<NotificationPermission>>();
  onclick: (() => void) | null = null;
  close = vi.fn();
  constructor(public title: string, public options?: NotificationOptions) { instances.push(this); }
}
function TestNotifications() {
  useMailNotifications(true, false, 'inbox');
  const location = useLocation();
  return <output>{location.pathname}{location.search}</output>;
}

let node: HTMLDivElement;
let root: Root;
beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  useLocaleStore.setState({ locale: 'en' });
  instances.length = 0;
  MockNotification.permission = 'granted';
  MockNotification.requestPermission.mockReset();
  vi.stubGlobal('Notification', MockNotification);
  node = document.createElement('div'); document.body.appendChild(node); root = createRoot(node);
});
afterEach(async () => {
  await act(async () => root.unmount());
  node.remove();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('mail notifications', () => {
  it('ignores the initial inbox snapshot, notifies only for new unread UIDs, and opens the message', async () => {
    vi.useFakeTimers();
    vi.spyOn(window, 'focus').mockImplementation(() => undefined);
    vi.spyOn(mailApi, 'messages')
      .mockResolvedValueOnce([message(5), message(4)])
      .mockResolvedValueOnce([message(6), message(5), message(4)])
      .mockResolvedValueOnce([message(7, true), message(6), message(5)]);
    await act(async () => root.render(<MemoryRouter initialEntries={['/mail']}><TestNotifications /></MemoryRouter>));
    expect(instances).toHaveLength(0);
    await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });
    expect(instances).toHaveLength(1);
    expect(instances[0].title).toBe('New message');
    expect(instances[0].options?.body).toBe('New inbox messages: 1');
    await act(async () => instances[0].onclick?.());
    expect(node.textContent).toBe('/mail?mailId=m6');
    await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });
    expect(instances).toHaveLength(1);
  });

  it('does not present revoked permissions as enabled', async () => {
    MockNotification.permission = 'denied';
    const save = vi.fn().mockReturnValue(true);
    await act(async () => root.render(<MailSettingsModal initial={{ ...DEFAULT_MAIL_PREFERENCES, notifications: true }} email="me@example.com" demoMode={false} onClose={() => undefined} onSave={save} />));
    const label = [...node.querySelectorAll('label')].find((item) => item.textContent?.includes('Notify me about new inbox messages'))!;
    expect(label.querySelector('input')?.checked).toBe(false);
    expect(node.querySelector('[role="alert"]')?.textContent).toContain('no longer allows notifications');
    await act(async () => node.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ notifications: false }));
  });

  it('requests browser permission only when enabled and reports a denial', async () => {
    MockNotification.permission = 'default';
    MockNotification.requestPermission.mockResolvedValue('denied');
    const save = vi.fn().mockReturnValue(true);
    await act(async () => root.render(<MailSettingsModal initial={DEFAULT_MAIL_PREFERENCES} email="me@example.com" demoMode={false} onClose={() => undefined} onSave={save} />));
    const label = [...node.querySelectorAll('label')].find((item) => item.textContent?.includes('Notify me about new inbox messages'))!;
    const control = label.querySelector('input')!;
    expect(MockNotification.requestPermission).not.toHaveBeenCalled();
    await act(async () => { control.click(); await Promise.resolve(); });
    expect(MockNotification.requestPermission).toHaveBeenCalledTimes(1);
    expect(control.checked).toBe(false);
    expect(node.querySelector('[role="alert"]')?.textContent).toContain('did not allow notifications');
    expect(save).not.toHaveBeenCalled();
  });

  it.each(['close', 'cancel', 'escape', 'backdrop', 'security', 'external'])
  ('allows %s while permission is pending without saving a late response', async (action) => {
    MockNotification.permission = 'default';
    let resolvePermission!: (value: NotificationPermission) => void;
    MockNotification.requestPermission.mockReturnValue(new Promise((resolve) => { resolvePermission = resolve; }));
    const save = vi.fn().mockReturnValue(true);
    const closed = vi.fn();
    function Settings() {
      const [open, setOpen] = useState(true);
      const close = () => { closed(action); setOpen(false); };
      return open ? <MailSettingsModal initial={DEFAULT_MAIL_PREFERENCES} email="me@example.com" demoMode={false}
        onClose={close} onSave={save} onOpenSecurity={close} onOpenExternal={close} /> : <output>Closed</output>;
    }
    await act(async () => root.render(<Settings />));
    const control = [...node.querySelectorAll('label')].find((item) => item.textContent?.includes('Notify me about new inbox messages'))!.querySelector('input')!;
    await act(async () => control.click());
    expect(MockNotification.requestPermission).toHaveBeenCalledTimes(1);
    expect(control.disabled).toBe(true);
    expect(node.querySelector('[role="status"]')?.textContent).toContain('Waiting for browser permission');
    await act(async () => node.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
    expect(save).not.toHaveBeenCalled();
    await act(async () => {
      if (action === 'escape') window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      else if (action === 'backdrop') (node.querySelector('[role="dialog"]')!.parentElement as HTMLElement).click();
      else if (action === 'close') (node.querySelector('button[aria-label="Close settings"]') as HTMLButtonElement).click();
      else {
        const label = action === 'cancel' ? 'Cancel' : action === 'security' ? 'Security and 2FA' : 'External mailboxes';
        [...node.querySelectorAll('button')].find((button) => button.textContent?.trim() === label)!.click();
      }
    });
    expect(closed).toHaveBeenCalledTimes(1);
    expect(node.querySelector('[role="dialog"]')).toBeNull();
    await act(async () => { resolvePermission('granted'); await Promise.resolve(); });
    expect(node.textContent).toBe('Closed');
    expect(save).not.toHaveBeenCalled();
  });
});
