// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { getDemoUser } from '../../api/demoData';
import { ToastProvider } from '../../components/ToastProvider';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import MailPage from './MailPage';

describe('bulk mail selection', () => {
  it('moves the selected messages at once and offers an undo instead of a blocking confirm', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    localStorage.clear();
    useLocaleStore.setState({ locale: 'en' });
    useSession.getState().setSession(getDemoUser('en'), true);
    // Нативное подтверждение убрано: действие отменяемо, а блокирующий диалог
    // нельзя ни стилизовать, ни прочитать скринридером вместе с итогом операции.
    const confirm = vi.spyOn(window, 'confirm');
    const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
    await act(async () => { root.render(<ToastProvider><MemoryRouter><MailPage /></MemoryRouter></ToastProvider>); });
    const all = host.querySelector<HTMLInputElement>('[aria-label="Select all shown messages"]')!;
    expect(all).not.toBeNull();
    await act(async () => all.click());
    expect(host.textContent).toContain('Selected: 4');
    const actions = host.querySelector('[role="group"][aria-label="Bulk actions"]')!;
    const archive = Array.from(actions.querySelectorAll<HTMLButtonElement>('button')).find((button) => button.textContent?.trim() === 'Archive')!;
    expect(archive).toBeTruthy();
    await act(async () => { archive.click(); await Promise.resolve(); });
    expect(confirm).not.toHaveBeenCalled();
    expect(host.textContent).toContain('Messages moved: 4');
    expect(host.querySelectorAll('input[aria-label^="Select message:"]')).toHaveLength(0);
    // Итог операции продублирован в уведомлении: с включённым ToastProvider
    // (как в AppShell) там же появляется «Отменить».
    expect(host.textContent).toContain('Messages moved to “Archive”: 4');
    await act(async () => root.unmount()); host.remove(); confirm.mockRestore();
    useSession.getState().clearSession(); useLocaleStore.setState({ locale: 'ru' });
  });

  it('keeps filters, visible selection and clearing usable with the keyboard', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    localStorage.clear();
    useLocaleStore.setState({ locale: 'en' });
    // Предыдущий тест отметил письма прочитанными в общем демо-состоянии.
    useSession.getState().clearSession();
    useSession.getState().setSession(getDemoUser('en'), true);
    const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
    await act(async () => { root.render(<ToastProvider><MemoryRouter><MailPage /></MemoryRouter></ToastProvider>); });
    const all = host.querySelector<HTMLInputElement>('input[aria-label="Select all shown messages"]')!;
    const tabs = host.querySelector('[role="tablist"][aria-label="Mail filter"]')!;
    expect(tabs).toBeTruthy();
    // Selection and filters have separate rows even in the narrow desktop list pane.
    expect(tabs.previousElementSibling?.contains(all)).toBe(true);
    const tabButtons = Array.from(tabs.querySelectorAll<HTMLButtonElement>('[role="tab"]'));
    const [allTab, unreadTab, readTab] = tabButtons;
    expect(allTab.getAttribute('aria-selected')).toBe('true');
    expect(allTab.tabIndex).toBe(0);
    expect(unreadTab.tabIndex).toBe(-1);
    const panel = host.querySelector('[role="tabpanel"]')!;
    expect(allTab.getAttribute('aria-controls')).toBe(panel.id);
    expect(panel.getAttribute('aria-labelledby')).toBe(allTab.id);

    allTab.focus();
    await act(async () => allTab.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })));
    expect(document.activeElement).toBe(unreadTab);
    expect(unreadTab.getAttribute('aria-selected')).toBe('true');
    expect(unreadTab.tabIndex).toBe(0);
    expect(panel.getAttribute('aria-labelledby')).toBe(unreadTab.id);
    const readBeforeArrowDown = [...useSession.getState().demoReadMailIds];
    await act(async () => unreadTab.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true })));
    expect(useSession.getState().demoReadMailIds).toEqual(readBeforeArrowDown);
    const unread = host.querySelectorAll<HTMLInputElement>('input[aria-label^="Select message:"]');
    expect(unread.length).toBeGreaterThan(0);
    expect(unread.length).toBeLessThan(4);
    await act(async () => all.click());
    expect(host.textContent).toContain(`Selected: ${unread.length}`);
    expect(Array.from(unread).every((item) => item.checked)).toBe(true);

    await act(async () => unreadTab.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true })));
    expect(document.activeElement).toBe(readTab);
    expect(readTab.getAttribute('aria-selected')).toBe('true');
    expect(host.querySelector('[aria-label="Bulk actions"]')).toBeNull();
    expect(all.checked).toBe(false);
    const rows = host.querySelectorAll<HTMLInputElement>('input[aria-label^="Select message:"]');
    expect(rows.length + unread.length).toBe(4);
    await act(async () => rows[0].click());
    expect(host.textContent).toContain('Selected: 1');
    const clear = Array.from(host.querySelectorAll<HTMLButtonElement>('[aria-label="Bulk actions"] button'))
      .find((button) => button.textContent?.trim() === 'Clear selection')!;
    await act(async () => clear.click());
    expect(host.querySelector('[aria-label="Bulk actions"]')).toBeNull();
    expect(Array.from(rows).every((item) => !item.checked)).toBe(true);
    await act(async () => root.unmount()); host.remove();
    useSession.getState().clearSession(); useLocaleStore.setState({ locale: 'ru' });
  });

  it('moves the selected messages to the chosen folder without asking for confirmation', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    localStorage.clear();
    useLocaleStore.setState({ locale: 'en' });
    useSession.getState().setSession(getDemoUser('en'), true);
    const confirm = vi.spyOn(window, 'confirm');
    const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
    await act(async () => { root.render(<ToastProvider><MemoryRouter><MailPage /></MemoryRouter></ToastProvider>); });
    await act(async () => host.querySelector<HTMLInputElement>('input[aria-label="Select all shown messages"]')!.click());
    const select = host.querySelector<HTMLSelectElement>('select[aria-label="Move selected messages"]')!;
    expect(Array.from(select.options, (option) => option.value)).not.toContain('INBOX');
    expect(Array.from(select.options, (option) => option.value)).not.toContain('ARCHIVE');
    expect(Array.from(select.options, (option) => option.value)).not.toContain('TRASH');
    await act(async () => {
      select.value = 'SENT';
      select.dispatchEvent(new Event('change', { bubbles: true }));
      await Promise.resolve();
    });
    expect(confirm).not.toHaveBeenCalled();
    expect(select.value).toBe('');
    expect(host.textContent).toContain('Messages moved: 4');
    expect(host.querySelectorAll('input[aria-label^="Select message:"]')).toHaveLength(0);
    await act(async () => root.unmount()); host.remove(); confirm.mockRestore();
    useSession.getState().clearSession(); useLocaleStore.setState({ locale: 'ru' });
  });
});
