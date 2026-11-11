// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it } from 'vitest';
import MailHtmlFrame from './MailHtmlFrame';

describe('isolated HTML frame', () => {
  it('accepts only bounded resize messages from its own frame with its nonce', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    const host = document.createElement('div'); document.body.appendChild(host); const root = createRoot(host);
    await act(async () => root.render(<MailHtmlFrame html="<p>Hello</p>" />));
    const frame = host.querySelector('iframe')!;
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts');
    expect(frame.getAttribute('sandbox')).not.toContain('allow-same-origin');
    const nonce = frame.srcdoc.match(/script-src 'nonce-([0-9a-f]{32})'/)![1];
    const send = (source: MessageEventSource | null, token: string, height: number) => window.dispatchEvent(new MessageEvent('message', { source, data: { type: 'corvex-mail-height', nonce: token, height } }));
    await act(async () => { send(window, nonce, 500); send(frame.contentWindow, 'wrong', 500); });
    expect(frame.style.height).toBe('120px');
    await act(async () => send(frame.contentWindow, nonce, 350)); expect(frame.style.height).toBe('350px');
    await act(async () => send(frame.contentWindow, nonce, 999999)); expect(frame.style.height).toBe('20000px');
    await act(async () => send(frame.contentWindow, nonce, 20)); expect(frame.style.height).toBe('80px');
    await act(async () => root.unmount()); host.remove();
  });
});
