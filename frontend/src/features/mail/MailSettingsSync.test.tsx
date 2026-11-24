// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mailApi } from '../../api/mailApi';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import MailPage from './MailPage';
import { mailPreferencesKey, type MailPreferences } from './mailPreferences';

let container: HTMLDivElement;
let root: Root;

async function flush() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); });
}

const settings: MailPreferences = {
  density: 'compact',
  showPreview: false,
  conversations: false,
  markRead: 'manual',
  defaultSort: 'date-asc',
  signature: 'Пётр, Corvex',
  signatureForNew: true,
  signatureForReplies: true,
  notifications: false,
  signatureForSubmission: true,
};

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  useLocaleStore.setState({ locale: 'en' });
  useSession.getState().setSession({
    id: 'me', displayName: 'Me', email: 'me@example.com', groups: [], permissions: ['MAIL_READ_OWN'],
  }, false);
  vi.spyOn(mailApi, 'folders').mockResolvedValue([
    { id: 'inbox-id', name: 'Inbox', specialUse: 'INBOX', total: 0, unread: 0 },
    { id: 'sent-id', name: 'Sent', specialUse: 'SENT', total: 0, unread: 0 },
  ]);
  vi.spyOn(mailApi, 'quota').mockResolvedValue({ usedBytes: 0, quotaBytes: 0, messageCount: 0 });
  vi.spyOn(mailApi, 'messages').mockResolvedValue([]);
  vi.spyOn(mailApi, 'scheduledSends').mockResolvedValue([]);
  vi.spyOn(mailApi, 'settings').mockResolvedValue(settings);
  vi.spyOn(mailApi, 'saveSettings').mockImplementation(async (value) => value);
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

describe('server-side mail settings', () => {
  it('takes the signature and view from the server, not from this browser', async () => {
    // В браузере лежат старые настройки: сервер должен победить.
    localStorage.setItem(mailPreferencesKey('me', false)!, JSON.stringify({ signature: 'старая подпись' }));

    await act(async () => root.render(<MemoryRouter initialEntries={['/mail']}><MailPage /></MemoryRouter>));
    await flush();

    const stored = JSON.parse(localStorage.getItem(mailPreferencesKey('me', false)!)!);
    expect(stored.signature).toBe('Пётр, Corvex');
    expect(stored.signatureForSubmission).toBe(true);
    expect(stored.conversations).toBe(false);
  });

  it('falls back to the local copy when the settings endpoint fails', async () => {
    vi.mocked(mailApi.settings).mockRejectedValue(new Error('offline'));
    localStorage.setItem(mailPreferencesKey('me', false)!, JSON.stringify({ ...settings, signature: 'локальная копия' }));

    await act(async () => root.render(<MemoryRouter initialEntries={['/mail']}><MailPage /></MemoryRouter>));
    await flush();

    // Ничего не затёрто: письма по-прежнему открываются, настройки на месте.
    expect(container.textContent).toContain('Inbox');
    const stored = JSON.parse(localStorage.getItem(mailPreferencesKey('me', false)!)!);
    expect(stored.signature).toBe('локальная копия');
  });
});
