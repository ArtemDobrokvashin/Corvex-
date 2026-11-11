// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import { ComposePanel } from './MailPage';

describe('compose attachments', () => {
  it('adds dropped files and allows removing them without submitting', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    const node = document.createElement('div'); document.body.appendChild(node);
    const root = createRoot(node);
    const send = vi.fn();
    await act(async () => root.render(<ComposePanel initial={{ to: '', subject: '', body: '' }} currentEmail="a@b.test" externalAccounts={[]} onClose={() => {}} onSend={send} />));
    const form = node.querySelector('form')!;
    const event = new Event('drop', { bubbles: true, cancelable: true });
    Object.defineProperty(event, 'dataTransfer', { value: { files: [new File(['data'], 'test.txt')], types: ['Files'] } });
    await act(async () => form.dispatchEvent(event));
    expect(node.textContent).toContain('test.txt');
    const remove = node.querySelector('button[aria-label="Remove attachment: test.txt"]') as HTMLButtonElement;
    expect(remove).not.toBeNull();
    await act(async () => remove.click());
    expect(node.textContent).not.toContain('test.txt');
    expect(send).not.toHaveBeenCalled();
    await act(async () => root.unmount()); node.remove();
    useLocaleStore.setState({ locale: 'ru' });
  });
  it('expands and restores without losing content or attachments, and confirms closing', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    const node = document.createElement('div'); document.body.appendChild(node);
    const root = createRoot(node); const close = vi.fn();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    await act(async () => root.render(<ComposePanel initial={{ to: 'person@example.com', subject: 'Subject', body: 'Message' }} currentEmail="a@b.test" externalAccounts={[]} onClose={close} onSend={vi.fn()} />));
    const form = node.querySelector('form')!;
    const event = new Event('drop', { bubbles: true, cancelable: true });
    Object.defineProperty(event, 'dataTransfer', { value: { files: [new File(['data'], 'test.txt')], types: ['Files'] } });
    await act(async () => form.dispatchEvent(event));
    await act(async () => (node.querySelector('[aria-label="Expand compose window"]') as HTMLButtonElement).click());
    expect(node.querySelector('[aria-label="Restore compose window"]')?.getAttribute('aria-pressed')).toBe('true');
    expect(node.querySelector('[aria-label="Message body"]')?.textContent).toBe('Message');
    expect(node.textContent).toContain('test.txt');
    await act(async () => (node.querySelector('[aria-label="Restore compose window"]') as HTMLButtonElement).click());
    expect((node.querySelector('#compose-to') as HTMLInputElement).value).toBe('person@example.com');
    await act(async () => (node.querySelector('[aria-label="Close draft"]') as HTMLButtonElement).click());
    expect(confirm).toHaveBeenCalledOnce(); expect(close).not.toHaveBeenCalled();
    confirm.mockReturnValue(true);
    await act(async () => (node.querySelector('[aria-label="Close draft"]') as HTMLButtonElement).click());
    expect(close).toHaveBeenCalledOnce();
    await act(async () => root.unmount()); node.remove(); confirm.mockRestore(); useLocaleStore.setState({ locale: 'ru' });
  });

});
