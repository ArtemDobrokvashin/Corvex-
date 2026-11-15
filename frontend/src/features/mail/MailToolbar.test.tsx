// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import MailToolbar from './MailToolbar';

describe('mail toolbar', () => {
  it('offers accessible working actions and protects an open composer', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
    const reply = vi.fn(), forward = vi.fn(), archive = vi.fn(), remove = vi.fn(), flag = vi.fn(), step = vi.fn();
    const render = (composing: boolean) => <MailToolbar flagged composing={composing} index={0} total={2} onBack={vi.fn()} onReply={reply} onForward={forward} onArchive={archive} onDelete={remove} onFlag={flag} onStep={step} />;
    await act(async () => root.render(render(false)));
    const button = (name: string) => host.querySelector<HTMLButtonElement>(`button[aria-label="${name}"]`)!;
    await act(async () => { button('Reply').click(); button('Forward').click(); button('Archive').click(); });
    expect(reply).toHaveBeenCalledOnce(); expect(forward).toHaveBeenCalledOnce(); expect(archive).toHaveBeenCalledOnce();
    expect(button('Starred').getAttribute('aria-pressed')).toBe('true');
    expect(button('Previous message').disabled).toBe(true);
    await act(async () => root.render(render(true)));
    expect(button('Reply').disabled).toBe(true); expect(button('Forward').disabled).toBe(true);
    await act(async () => root.unmount()); host.remove(); useLocaleStore.setState({ locale: 'ru' });
  });
});
