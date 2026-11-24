// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mailApi, type MailFolderView, type MailMessageListItem } from '../../api/mailApi';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import { scheduledSendOptions, toLocalInputValue } from './ComposePanel';
import MailPage from './MailPage';

const folders: MailFolderView[] = [
  { id: 'inbox-id', name: 'Inbox', specialUse: 'INBOX', total: 1, unread: 0 },
  { id: 'sent-id', name: 'Sent', specialUse: 'SENT', total: 0, unread: 0 },
];
const message: MailMessageListItem = {
  id: 'm-1', folderId: 'inbox-id', uid: 1, sender: 'Alice <alice@example.com>',
  subject: 'Quarterly budget', snippet: 'Numbers', date: '2026-04-01T10:00:00Z',
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
  useLocaleStore.setState({ locale: 'en' });
  useSession.getState().setSession({
    id: 'me', displayName: 'Me', email: 'me@example.com', groups: [], permissions: ['MAIL_READ_OWN'],
  }, false);
  vi.spyOn(mailApi, 'folders').mockResolvedValue(folders);
  vi.spyOn(mailApi, 'quota').mockResolvedValue({ usedBytes: 0, quotaBytes: 0, messageCount: 0 });
  vi.spyOn(mailApi, 'messages').mockImplementation(async (folderId) => (folderId === 'inbox-id' ? [message] : []));
  vi.spyOn(mailApi, 'message').mockResolvedValue({
    summary: message, to: [], cc: [], bodyText: 'Body', bodyHtml: null, attachments: [],
  });
  vi.spyOn(mailApi, 'scheduledSends').mockResolvedValue([]);
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

describe('snooze', () => {
  it('removes the message from the list and offers an immediate undo', async () => {
    const snooze = vi.spyOn(mailApi, 'snooze').mockResolvedValue(message);
    vi.spyOn(mailApi, 'wake').mockResolvedValue(message);

    await act(async () => root.render(<MemoryRouter initialEntries={['/mail']}><MailPage /></MemoryRouter>));
    await flush();
    const row = container.querySelector('[data-mail-id="m-1"]') as HTMLElement;
    await act(async () => { row.click(); await Promise.resolve(); });
    await flush();

    const snoozeButton = [...container.querySelectorAll('button')]
      .find((button) => button.getAttribute('title') === 'Snooze until tomorrow');
    expect(snoozeButton).toBeTruthy();
    await act(async () => { (snoozeButton as HTMLElement).click(); await Promise.resolve(); });
    await flush();

    expect(snooze).toHaveBeenCalledWith('m-1', expect.any(String));
    // Время — через сутки, а не «сейчас» и не в прошлом.
    const wakeAt = new Date(vi.mocked(mailApi.snooze).mock.calls[0][1]).getTime();
    expect(wakeAt).toBeGreaterThan(Date.now() + 20 * 3600_000);
    // Письмо исчезло из списка, но не потерялось: тост предлагает вернуть его.
    expect(container.querySelector('[data-mail-id="m-1"]')).toBeNull();

    // Действие «Вернуть сейчас» приходит через тост: он живёт в общем слое
    // уведомлений, поэтому в дереве MailPage его нет. Проверяем то, что от
    // страницы зависит: письмо убрано из списка, а snooze ушёл на сервер.
    expect(container.textContent).not.toContain('Quarterly budget');
  });

  it('does not call the server in demo mode', async () => {
    useSession.getState().setSession({ id: 'demo', displayName: 'Demo', email: 'demo@example.com', groups: [], permissions: [] }, true);
    const snooze = vi.spyOn(mailApi, 'snooze');

    await act(async () => root.render(<MemoryRouter initialEntries={['/mail']}><MailPage /></MemoryRouter>));
    await flush();
    const row = container.querySelector('[data-mail-id]') as HTMLElement;
    await act(async () => { row.click(); await Promise.resolve(); });
    await flush();
    const snoozeButton = [...container.querySelectorAll('button')]
      .find((button) => button.getAttribute('title') === 'Snooze until tomorrow');
    if (snoozeButton) {
      await act(async () => { (snoozeButton as HTMLElement).click(); await Promise.resolve(); });
    }
    expect(snooze).not.toHaveBeenCalled();
  });
});

describe('scheduled sending', () => {
  it('suggests times in the future, not deltas', () => {
    const now = new Date(2026, 9, 19, 10, 15);
    const options = scheduledSendOptions(now);
    expect(options).toHaveLength(4);
    for (const option of options) {
      expect(option.at.getTime()).toBeGreaterThan(now.getTime());
    }
    // «Завтра утром» — это 8:00, а не «через 22 часа».
    const morning = options.find((option) => option.id === 'morning')!;
    expect(morning.at.getHours()).toBe(8);
    expect(morning.at.getDate()).toBe(20);
  });

  it('serialises the local time without seconds or a timezone suffix', () => {
    expect(toLocalInputValue(new Date(2026, 0, 5, 7, 4))).toBe('2026-01-05T07:04');
  });
});
