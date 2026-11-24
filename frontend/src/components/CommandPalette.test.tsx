// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../i18n';
import CommandPalette from './CommandPalette';

let container: HTMLDivElement;
let root: Root;

function setValue(element: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(element, value);
  element.dispatchEvent(new Event('input', { bubbles: true }));
}

async function renderPalette(demoMode = false) {
  await act(async () => root.render(
    <MemoryRouter>
      <CommandPalette
        open
        onClose={vi.fn()}
        canSeeAdmin={false}
        onToggleCalendarWidget={vi.fn()}
        onOpenSecurity={vi.fn()}
        onLogout={vi.fn()}
        demoMode={demoMode}
      />
    </MemoryRouter>,
  ));
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
  vi.restoreAllMocks();
});

describe('⌘K palette', () => {
  it('shows mail results next to commands for a two-letter query', async () => {
    vi.useFakeTimers();
    const { mailApi } = await import('../api/mailApi');
    const search = vi.spyOn(mailApi, 'search').mockResolvedValue([
      {
        messageId: 'm-42',
        folderId: 'INBOX',
        uid: 42,
        sender: 'Alice <alice@example.com>',
        subject: 'Quarterly budget',
        snippet: 'Numbers for Q3',
        internalDate: '2026-10-01T09:00:00Z',
      },
    ]);
    const { contactsApi } = await import('../api/contactsApi');
    vi.spyOn(contactsApi, 'list').mockResolvedValue([]);

    await renderPalette();
    const input = container.querySelector('input')!;
    await act(async () => setValue(input, 'budget'));
    await act(async () => { vi.advanceTimersByTime(400); });

    expect(search).toHaveBeenCalledWith('budget', 5, expect.any(AbortSignal));
    expect(container.textContent).toContain('Quarterly budget');
    expect(container.textContent).toContain('alice@example.com');
    vi.useRealTimers();
  });

  it('says the mail index is unavailable instead of looking empty on 503', async () => {
    vi.useFakeTimers();
    const { mailApi } = await import('../api/mailApi');
    vi.spyOn(mailApi, 'search').mockRejectedValue(Object.assign(new Error('unavailable'), { status: 503 }));
    const { contactsApi } = await import('../api/contactsApi');
    vi.spyOn(contactsApi, 'list').mockResolvedValue([]);

    await renderPalette();
    await act(async () => setValue(container.querySelector('input')!, 'budget'));
    await act(async () => { vi.advanceTimersByTime(400); });

    expect(container.textContent).toContain('Mail search is temporarily unavailable.');
    vi.useRealTimers();
  });

  it('does not call the server in demo mode', async () => {
    vi.useFakeTimers();
    const { mailApi } = await import('../api/mailApi');
    const search = vi.spyOn(mailApi, 'search');
    const { contactsApi } = await import('../api/contactsApi');
    const list = vi.spyOn(contactsApi, 'list');

    await renderPalette(true);
    await act(async () => setValue(container.querySelector('input')!, 'budget'));
    await act(async () => { vi.advanceTimersByTime(400); });

    expect(search).not.toHaveBeenCalled();
    expect(list).not.toHaveBeenCalled();
    vi.useRealTimers();
  });
});
