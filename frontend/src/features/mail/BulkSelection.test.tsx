// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import { BulkActionBar, SelectMessage, SelectVisibleMessages } from './BulkSelection';

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
    await act(async () => root.render(<SelectVisibleMessages visibleCount={3} selectedCount={3} disabled onChange={selectAll} />));
    expect(host.querySelector<HTMLInputElement>('input')!.checked).toBe(true);
    expect(host.querySelector<HTMLInputElement>('input')!.disabled).toBe(true);
    await act(async () => root.unmount()); host.remove(); useLocaleStore.setState({ locale: 'ru' });
  });

  it('offers distinct folder moves and forwarding without duplicate destinations', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
    const onMove = vi.fn(), onForward = vi.fn(), onClear = vi.fn();
    const folders = [
      { id: 'INBOX', name: 'Inbox' }, { id: 'ARCHIVE', name: 'Archive' },
      { id: 'TRASH', name: 'Trash' }, { id: 'project-id', name: 'Project' },
    ];
    await act(async () => root.render(<BulkActionBar count={2} folders={folders} currentFolder="INBOX" busy={false}
      onMove={onMove} onForward={onForward} onClear={onClear} />));

    const group = host.querySelector('[role="group"][aria-label="Bulk actions"]')!;
    expect(group.textContent).toContain('Selected: 2');
    const select = group.querySelector<HTMLSelectElement>('select[aria-label="Move selected messages"]')!;
    expect(select.options[0].textContent).toBe('To folder');
    expect(select.options[0].disabled).toBe(true);
    expect(Array.from(select.options, (option) => option.value)).toEqual(['', 'project-id']);
    const button = (text: string) => Array.from(group.querySelectorAll<HTMLButtonElement>('button'))
      .find((item) => item.textContent?.trim() === text)!;

    await act(async () => button('Archive').click());
    await act(async () => button('Move to Trash').click());
    await act(async () => { select.value = 'project-id'; select.dispatchEvent(new Event('change', { bubbles: true })); });
    expect(onMove.mock.calls).toEqual([['ARCHIVE'], ['TRASH'], ['project-id']]);
    expect(select.value).toBe('');
    await act(async () => group.querySelector<HTMLButtonElement>('button[aria-label="Forward selected"]')!.click());
    await act(async () => button('Clear selection').click());
    expect(onForward).toHaveBeenCalledOnce();
    expect(onClear).toHaveBeenCalledOnce();
    await act(async () => root.unmount()); host.remove(); useLocaleStore.setState({ locale: 'ru' });
  });

  it('disables unavailable, current and busy destinations and localizes the controls', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'ru' });
    const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
    const onMove = vi.fn(), onForward = vi.fn(), onClear = vi.fn();
    const folders = [{ id: 'ARCHIVE', name: 'Архив' }, { id: 'INBOX', name: 'Входящие' }];
    const render = (busy: boolean) => <BulkActionBar count={1} folders={folders} currentFolder="ARCHIVE" busy={busy}
      onMove={onMove} onForward={onForward} onClear={onClear} />;
    await act(async () => root.render(render(false)));
    const group = host.querySelector('[role="group"][aria-label="Групповые действия"]')!;
    const buttons = Array.from(group.querySelectorAll<HTMLButtonElement>('button'));
    const archive = buttons.find((item) => item.textContent?.trim() === 'В архив')!;
    const trash = buttons.find((item) => item.textContent?.trim() === 'В корзину')!;
    expect(archive.disabled).toBe(true);
    expect(archive.getAttribute('aria-label')).toContain('Письма уже в этой папке');
    expect(trash.disabled).toBe(true);
    expect(trash.getAttribute('aria-label')).toContain('Папка недоступна');
    expect(group.querySelector('button[aria-label="Переслать выбранные"]')?.textContent).toContain('Переслать');
    const select = group.querySelector<HTMLSelectElement>('select[aria-label="Переместить выбранные письма"]')!;
    expect(Array.from(select.options, (option) => option.value)).toEqual(['', 'INBOX']);

    await act(async () => root.render(render(true)));
    expect(group.getAttribute('aria-busy')).toBe('true');
    expect(Array.from(group.querySelectorAll<HTMLButtonElement | HTMLSelectElement>('button, select')).every((control) => control.disabled)).toBe(true);
    await act(async () => root.render(<BulkActionBar count={1} folders={[folders[0]]} currentFolder="ARCHIVE" busy={false}
      onMove={onMove} onForward={onForward} onClear={onClear} />));
    expect(group.querySelector<HTMLSelectElement>('select')!.disabled).toBe(true);
    expect(group.querySelector<HTMLSelectElement>('select')!.getAttribute('aria-label')).toContain('Нет других доступных папок');
    await act(async () => useLocaleStore.setState({ locale: 'en' }));
    expect(group.querySelector<HTMLSelectElement>('select')!.getAttribute('aria-label')).toContain('No other folders available');
    expect(group.querySelector<HTMLButtonElement>('button[title="Folder is unavailable"]')!.disabled).toBe(true);
    expect(onMove).not.toHaveBeenCalled();
    await act(async () => root.unmount()); host.remove(); useLocaleStore.setState({ locale: 'ru' });
  });
});
