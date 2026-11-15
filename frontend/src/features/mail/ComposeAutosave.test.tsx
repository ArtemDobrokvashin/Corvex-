// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';

const createDraft = vi.fn();
const updateDraft = vi.fn();
const deleteDraft = vi.fn();
vi.mock('../../api/mailApi', async (importOriginal) => {
  const original = await importOriginal<typeof import('../../api/mailApi')>();
  return { ...original, mailApi: { ...original.mailApi, createDraft, updateDraft, deleteDraft } };
});

const { ComposePanel } = await import('./ComposePanel');

afterEach(() => {
  vi.useRealTimers();
  createDraft.mockReset(); updateDraft.mockReset(); deleteDraft.mockReset();
  useLocaleStore.setState({ locale: 'ru' });
});

async function renderComposer(onSend = vi.fn()) {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  useLocaleStore.setState({ locale: 'en' });
  const node = document.createElement('div');
  document.body.appendChild(node);
  const root = createRoot(node);
  await act(async () => root.render(
    <ComposePanel initial={{ to: 'a@b.test', subject: 'Subject', body: 'Body' }} currentEmail="me@b.test"
      externalAccounts={[]} onClose={() => {}} onSend={onSend} autoSave />,
  ));
  return { node, root };
}

describe('draft autosave', () => {
  it('creates one draft, then updates it with the server revision', async () => {
    vi.useFakeTimers();
    createDraft.mockResolvedValue({ id: 'draft-1', revision: 1, payload: {}, updatedAt: '2026-01-01T00:00:00Z' });
    updateDraft.mockResolvedValue({ id: 'draft-1', revision: 2, payload: {}, updatedAt: '2026-01-01T00:00:10Z' });
    const { node, root } = await renderComposer();
    await act(async () => { vi.advanceTimersByTime(1600); });
    await act(async () => { await Promise.resolve(); });
    expect(createDraft).toHaveBeenCalledOnce();
    expect(createDraft.mock.calls[0][0]).toMatchObject({ to: 'a@b.test', subject: 'Subject', bodyText: 'Body' });
    expect(node.textContent).toContain('Draft saved');

    const to = node.querySelector('#compose-to') as HTMLInputElement;
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
      setter.call(to, 'other@b.test');
      to.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await act(async () => { vi.advanceTimersByTime(1600); });
    await act(async () => { await Promise.resolve(); });
    expect(updateDraft).toHaveBeenCalledOnce();
    expect(updateDraft.mock.calls[0][0]).toBe('draft-1');
    expect(updateDraft.mock.calls[0][1]).toBe(1);
    await act(async () => root.unmount()); node.remove();
  });

  it('does not call the server in demo mode', async () => {
    vi.useFakeTimers();
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    const node = document.createElement('div'); document.body.appendChild(node);
    const root = createRoot(node);
    await act(async () => root.render(
      <ComposePanel initial={{ to: 'a@b.test', subject: 'S', body: 'B' }} currentEmail="me@b.test"
        externalAccounts={[]} onClose={() => {}} onSend={vi.fn()} autoSave={false} />,
    ));
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(createDraft).not.toHaveBeenCalled();
    await act(async () => root.unmount()); node.remove();
  });

  it('reports a save failure instead of pretending success', async () => {
    vi.useFakeTimers();
    createDraft.mockRejectedValue(new Error('503'));
    const { node, root } = await renderComposer();
    await act(async () => { vi.advanceTimersByTime(1600); });
    await act(async () => { await Promise.resolve(); });
    expect(node.textContent).toContain('not saved');
    await act(async () => root.unmount()); node.remove();
  });
});
