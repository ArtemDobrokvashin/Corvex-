// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import CreateFolder from './CreateFolder';

describe('create folder', () => {
  it('submits a name and closes on success', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' }); const host = document.createElement('div'); document.body.append(host);
    const root = createRoot(host), create = vi.fn().mockResolvedValue(undefined);
    await act(async () => root.render(<CreateFolder onCreate={create} />));
    await act(async () => host.querySelector('button')!.click());
    const input = host.querySelector('input')!;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    await act(async () => { setter.call(input, '  Projects  '); input.dispatchEvent(new Event('input', { bubbles: true })); });
    await act(async () => { host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await Promise.resolve(); });
    expect(create).toHaveBeenCalledWith('Projects'); expect(host.querySelector('form')).toBeNull();
    await act(async () => root.unmount()); host.remove(); useLocaleStore.setState({ locale: 'ru' });
  });
  it('keeps the form open and displays server errors', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' }); const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
    await act(async () => root.render(<CreateFolder onCreate={vi.fn().mockRejectedValue(new Error('Already exists'))} />));
    await act(async () => host.querySelector('button')!.click());
    const input = host.querySelector('input')!;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    await act(async () => { setter.call(input, 'Projects'); input.dispatchEvent(new Event('input', { bubbles: true })); });
    await act(async () => { host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await Promise.resolve(); });
    expect(host.querySelector('[role=alert]')?.textContent).toBe('Already exists'); expect(input.value).toBe('Projects');
    await act(async () => root.unmount()); host.remove(); useLocaleStore.setState({ locale: 'ru' });
  });
});
