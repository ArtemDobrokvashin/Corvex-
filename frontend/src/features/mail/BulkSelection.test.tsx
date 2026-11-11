// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import { SelectMessage, SelectVisibleMessages } from './BulkSelection';

describe('bulk selection controls', () => {
  it('marks partial selection and prevents row click when selecting a message', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
    const selectAll = vi.fn(), selectOne = vi.fn(), open = vi.fn();
    await act(async () => root.render(<div onClick={open}>
      <SelectVisibleMessages visibleCount={3} selectedCount={1} onChange={selectAll} />
      <SelectMessage subject="Test" checked={false} onChange={selectOne} />
    </div>));
    const all = host.querySelector<HTMLInputElement>('[aria-label="Select all shown messages"]')!;
    expect(all.indeterminate).toBe(true); expect(all.checked).toBe(false);
    await act(async () => host.querySelector<HTMLInputElement>('[aria-label="Select message: Test"]')!.click());
    expect(selectOne).toHaveBeenCalledOnce(); expect(open).not.toHaveBeenCalled();
    await act(async () => root.render(<SelectVisibleMessages visibleCount={0} selectedCount={0} onChange={selectAll} />));
    expect(host.querySelector<HTMLInputElement>('input')!.disabled).toBe(true);
    await act(async () => root.unmount()); host.remove(); useLocaleStore.setState({ locale: 'ru' });
  });
});
