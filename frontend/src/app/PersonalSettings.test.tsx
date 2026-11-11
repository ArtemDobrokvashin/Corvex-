// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../i18n';
import PersonalSettings, { readWorkspaceBackground, saveWorkspaceBackground } from './PersonalSettings';

describe('personal settings', () => {
  afterEach(() => { localStorage.clear(); useLocaleStore.setState({ locale: 'ru' }); vi.restoreAllMocks(); });
  it('persists a validated background choice and falls back for unknown values', () => {
    expect(readWorkspaceBackground()).toBe('soft');
    expect(saveWorkspaceBackground('plain')).toBe(true);
    expect(readWorkspaceBackground()).toBe('plain');
    localStorage.setItem('corvex.workspaceBackground', 'invalid');
    expect(readWorkspaceBackground()).toBe('soft');
  });
  it('handles unavailable browser storage', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    expect(readWorkspaceBackground()).toBe('soft');
    expect(saveWorkspaceBackground('plain')).toBe(false);
  });
  it('exposes controls in English and reports save failure without closing', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    const host = document.createElement('div'); document.body.append(host);
    const root = createRoot(host), change = vi.fn().mockReturnValue(false), close = vi.fn(), security = vi.fn();
    await act(async () => root.render(<PersonalSettings background="soft" onBackground={change} onClose={close} onSecurity={security} />));
    expect(host.querySelector('[role=dialog]')?.textContent).toContain('Personal settings');
    const solid = Array.from(host.querySelectorAll('button')).find((button) => button.textContent === 'Solid')!;
    await act(async () => solid.click());
    expect(change).toHaveBeenCalledWith('plain');
    expect(host.querySelector('[role=alert]')?.textContent).toContain('Could not save');
    expect(close).not.toHaveBeenCalled();
    await act(async () => host.querySelector('[role=dialog]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })));
    expect(close).toHaveBeenCalledOnce();
    await act(async () => root.unmount()); host.remove();
  });
});
