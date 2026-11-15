// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';

const drafts = vi.fn();
const deleteDraft = vi.fn();
vi.mock('../../api/mailApi', async (importOriginal) => {
  const original = await importOriginal<typeof import('../../api/mailApi')>();
  return { ...original, mailApi: { ...original.mailApi, drafts, deleteDraft } };
});

const { default: DraftsPanel } = await import('./DraftsPanel');

afterEach(() => {
  drafts.mockReset(); deleteDraft.mockReset();
  useLocaleStore.setState({ locale: 'ru' });
});

async function renderPanel(handlers: { onOpen?: (id: string) => void; onClose?: () => void } = {}) {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  useLocaleStore.setState({ locale: 'en' });
  const node = document.createElement('div');
  document.body.appendChild(node);
  const root = createRoot(node);
  await act(async () => root.render(
    <DraftsPanel onOpen={handlers.onOpen ?? (() => {})} onClose={handlers.onClose ?? (() => {})} />,
  ));
  await act(async () => { await Promise.resolve(); });
  return { node, root };
}

const sample = {
  id: 'draft-1', revision: 3, to: 'client@example.com', subject: 'Proposal',
  attachmentCount: 2, sizeBytes: 2048, updatedAt: '2026-02-17T10:30:00Z',
};

describe('drafts panel', () => {
  it('lists saved drafts with recipients and sizes, and never shows bodies', async () => {
    drafts.mockResolvedValue([sample]);
    const { node, root } = await renderPanel();
    expect(drafts).toHaveBeenCalledOnce();
    expect(node.textContent).toContain('Proposal');
    expect(node.textContent).toContain('client@example.com');
    expect(node.textContent).toContain('2 KB');
    expect(node.textContent).toContain('2 att.');
    expect(node.textContent).not.toContain('bodyText');
    await act(async () => root.unmount()); node.remove();
  });

  it('opens a draft only after an explicit click', async () => {
    drafts.mockResolvedValue([sample]);
    const onOpen = vi.fn();
    const { node, root } = await renderPanel({ onOpen });
    expect(onOpen).not.toHaveBeenCalled();
    const button = Array.from(node.querySelectorAll('button')).find((item) => item.textContent?.includes('Proposal'))!;
    await act(async () => button.click());
    expect(onOpen).toHaveBeenCalledWith('draft-1');
    await act(async () => root.unmount()); node.remove();
  });

  it('deletes with the revision it displayed and removes the row', async () => {
    drafts.mockResolvedValue([sample]);
    deleteDraft.mockResolvedValue(undefined);
    const changed = vi.fn();
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    const node = document.createElement('div'); document.body.appendChild(node);
    useLocaleStore.setState({ locale: 'en' });
    const root = createRoot(node);
    await act(async () => root.render(<DraftsPanel onOpen={() => {}} onClose={() => {}} onChanged={changed} />));
    await act(async () => { await Promise.resolve(); });
    const remove = Array.from(node.querySelectorAll('button')).find((item) => item.getAttribute('aria-label')?.includes('Proposal'))! as HTMLButtonElement;
    await act(async () => remove.click());
    await act(async () => { await Promise.resolve(); });
    expect(deleteDraft).toHaveBeenCalledWith('draft-1', 3);
    expect(node.textContent).toContain('No saved drafts.');
    expect(changed).toHaveBeenCalledOnce();
    await act(async () => root.unmount()); node.remove();
  });

  it('reports a failed load instead of an empty list', async () => {
    drafts.mockRejectedValue(new Error('503 Service Unavailable'));
    const { node, root } = await renderPanel();
    expect(node.textContent).toContain('503');
    expect(node.textContent).not.toContain('No saved drafts.');
    await act(async () => root.unmount()); node.remove();
  });

  it('closes on Escape', async () => {
    drafts.mockResolvedValue([]);
    const onClose = vi.fn();
    const { node, root } = await renderPanel({ onClose });
    await act(async () => { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); });
    expect(onClose).toHaveBeenCalled();
    await act(async () => root.unmount()); node.remove();
  });
});
