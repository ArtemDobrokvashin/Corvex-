// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { getDemoUser } from '../../api/demoData';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import MailPage from './MailPage';

describe('bulk mail selection', () => {
  it('selects shown messages and moves them to archive only after confirmation', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    useSession.getState().setSession(getDemoUser('en'), true);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
    await act(async () => { root.render(<MemoryRouter><MailPage /></MemoryRouter>); });
    const all = host.querySelector<HTMLInputElement>('[aria-label="Select all shown messages"]')!;
    expect(all).not.toBeNull();
    await act(async () => all.click());
    expect(host.textContent).toContain('Selected: 4');
    const archive = Array.from(host.querySelectorAll('button.btn-secondary')).find((button) => button.textContent === 'Archive')!;
    expect(archive).toBeTruthy();
    await act(async () => archive.click());
    expect(host.textContent).toContain('Selected: 4');
    confirm.mockReturnValue(true);
    await act(async () => { archive.click(); await Promise.resolve(); });
    expect(host.textContent).toContain('Messages moved: 4');
    expect(host.querySelectorAll('input[aria-label^="Select message:"]')).toHaveLength(0);
    await act(async () => root.unmount()); host.remove(); confirm.mockRestore();
    useSession.getState().clearSession(); useLocaleStore.setState({ locale: 'ru' });
  });
});
