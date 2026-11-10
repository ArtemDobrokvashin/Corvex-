// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it, vi } from 'vitest';
import GroupPickerPopover from './GroupPickerPopover';

describe('group selector', () => {
  it('renders outside the table and closes with Escape', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    const host = document.createElement('div'); document.body.appendChild(host);
    const root = createRoot(host);
    const close = vi.fn();
    await act(async () => root.render(<GroupPickerPopover anchor={{ right: 400, bottom: 100 } as DOMRect} onClose={close} onSave={() => {}}><button type="button">Group</button></GroupPickerPopover>));
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(document.body.querySelector('[role="dialog"]')).not.toBeNull();
    await act(async () => window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })));
    expect(close).toHaveBeenCalledOnce();
    await act(async () => root.unmount()); host.remove();
  });
});
