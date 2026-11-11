// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import { cleanComposeHtml } from './ComposeEditor';
import { ComposePanel } from './MailPage';

describe('compose HTML editor', () => {
  it('strips executable content, unsafe links and CSS while preserving basic formatting', () => {
    const result = cleanComposeHtml('<p onclick="alert(1)"><strong>Test</strong><span style="color:#ff0000;background:url(https://tracker.test/x)">red</span><a href="javascript:alert(1)">bad</a><a href="https://example.com">safe</a><img src="https://tracker.test/x"><script>alert(1)</script></p>');
    expect(result).toContain('<strong>Test</strong>'); expect(result).toContain('color:');
    expect(result).toContain('href="https://example.com"'); expect(result).not.toMatch(/onclick|javascript:|tracker|script|background/i);
  });
  it('sends sanitized HTML and matching plain text after editing source', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    const node = document.createElement('div'); document.body.appendChild(node); const root = createRoot(node); const send = vi.fn().mockResolvedValue(undefined);
    await act(async () => root.render(<ComposePanel initial={{ to: 'a@b.test', subject: 'Test', body: 'Hello' }} currentEmail="a@b.test" externalAccounts={[]} onClose={() => {}} onSend={send} />));
    await act(async () => (node.querySelector('[aria-label="HTML source"]') as HTMLButtonElement).click());
    const input = node.querySelector('[aria-label="Message HTML source"]') as HTMLTextAreaElement;
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!;
    await act(async () => { setter.call(input, '<p><strong>Hello</strong> <a href="https://example.com">world</a><img src="https://tracker.test"></p>'); input.dispatchEvent(new Event('input', { bubbles: true })); });
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => (node.querySelector('[aria-label="HTML source"]') as HTMLButtonElement).click());
    expect(node.querySelector('[aria-label="Message body"] strong')?.textContent).toBe('Hello');
    confirm.mockRestore();
    await act(async () => { node.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await Promise.resolve(); });
    expect(send).toHaveBeenCalledOnce();
    const sent = send.mock.calls[0][0]; expect(sent.body).toBe('Hello world (https://example.com)'); expect(sent.bodyHtml).toContain('<strong>Hello</strong>');
    expect(sent.bodyHtml).toContain('href="https://example.com"'); expect(sent.bodyHtml).not.toContain('tracker');
    await act(async () => root.unmount()); node.remove(); useLocaleStore.setState({ locale: 'ru' });
  });
  it('previews source and sends a sanitized button without silently converting it', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    const node = document.createElement('div'); document.body.appendChild(node); const root = createRoot(node); const send = vi.fn().mockResolvedValue(undefined);
    await act(async () => root.render(<ComposePanel initial={{ to: 'a@b.test', subject: 'Test', body: 'Hello' }} currentEmail="a@b.test" externalAccounts={[]} onClose={() => {}} onSend={send} />));
    await act(async () => (node.querySelector('[aria-label="HTML source"]') as HTMLButtonElement).click());
    const input = node.querySelector('[aria-label="Message HTML source"]') as HTMLTextAreaElement;
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!;
    await act(async () => { setter.call(input, '<button onclick="alert(1)">GGG</button><script>alert(2)</script>'); input.dispatchEvent(new Event('input', { bubbles: true })); });
    const preview = [...node.querySelectorAll('button')].find((button) => button.textContent === 'Preview HTML')!;
    await act(async () => preview.click());
    const frame = node.querySelector('iframe')!;
    expect(frame.srcdoc).toContain('<button type="button" disabled="">GGG</button>');
    expect(frame.srcdoc).not.toContain('alert(1)'); expect(frame.srcdoc).not.toContain('alert(2)');
    await act(async () => { node.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await Promise.resolve(); });
    expect(send.mock.calls[0][0]).toMatchObject({ body: 'GGG', bodyHtml: '<button>GGG</button>' });
    await act(async () => root.unmount()); node.remove(); useLocaleStore.setState({ locale: 'ru' });
  });
  it('keeps edited source if visual conversion is declined', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' }); const node = document.createElement('div'); document.body.appendChild(node); const root = createRoot(node);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    await act(async () => root.render(<ComposePanel initial={{ to: 'a@b.test', subject: 'Test', body: 'Hello' }} currentEmail="a@b.test" externalAccounts={[]} onClose={() => {}} onSend={vi.fn()} />));
    await act(async () => (node.querySelector('[aria-label="HTML source"]') as HTMLButtonElement).click());
    const input = node.querySelector('[aria-label="Message HTML source"]') as HTMLTextAreaElement;
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!;
    await act(async () => { setter.call(input, '<button>GGG</button>'); input.dispatchEvent(new Event('input', { bubbles: true })); });
    await act(async () => (node.querySelector('[aria-label="HTML source"]') as HTMLButtonElement).click());
    expect(confirm).toHaveBeenCalledOnce(); expect(node.querySelector('textarea')?.value).toBe('<button>GGG</button>');
    confirm.mockRestore(); await act(async () => root.unmount()); node.remove(); useLocaleStore.setState({ locale: 'ru' });
  });

});
