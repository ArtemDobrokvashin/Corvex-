// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import FolderList from './FolderList';

describe('folder navigation', () => {
  it.each([false, true])('uses a vertical list and selects a folder (compact: %s)', async (compact) => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    const host = document.createElement('div'); document.body.append(host);
    const root = createRoot(host), select = vi.fn();
    const longName = 'Long folder name '.repeat(20);
    await act(async () => root.render(<FolderList compact={compact} folders={[{ id: 'INBOX', name: 'Inbox' }, { id: 'custom', name: longName }]} selected="INBOX" counts={{ INBOX: 3 }} onSelect={select} />));
    const nav = host.querySelector('nav')!;
    expect(nav.getAttribute('aria-label')).toBe('Mail folders');
    expect(nav.className).toContain('overflow-x-hidden');
    expect(nav.className).toContain('scroll-area');
    expect(nav.className).not.toContain('overflow-x-auto');
    expect(nav.querySelector('[aria-current="page"]')?.textContent).toContain('Inbox');
    const folder = nav.querySelectorAll('button')[1];
    expect(folder.title).toBe(longName);
    expect(folder.querySelector('.truncate')?.textContent).toBe(longName);
    await act(async () => folder.click());
    expect(select).toHaveBeenCalledWith('custom');
    await act(async () => root.unmount()); host.remove(); useLocaleStore.setState({ locale: 'ru' });
  });
});
