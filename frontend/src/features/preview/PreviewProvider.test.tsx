// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import PreviewProvider, { usePreview } from './PreviewProvider';
import type { PreviewSource } from './types';

const items: PreviewSource[] = [
  { fileName: 'первый.txt', contentType: 'text/plain', load: async () => new Blob(['один']) },
  { fileName: 'второй.txt', contentType: 'text/plain', load: async () => new Blob(['два']) },
];

function Opener() {
  const { openPreview } = usePreview();
  return (
    <>
      <button type="button" onClick={() => openPreview(items, 99)}>open-last</button>
      <button type="button" onClick={() => openPreview([])}>open-empty</button>
    </>
  );
}

let container: HTMLDivElement;
let root: Root;

const flush = async () => {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
};

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  useLocaleStore.setState({ locale: 'ru' });
  URL.createObjectURL = vi.fn(() => 'blob:provider');
  URL.revokeObjectURL = vi.fn();
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  useLocaleStore.setState({ locale: 'ru' });
  vi.restoreAllMocks();
});

describe('PreviewProvider', () => {
  it('clamps the requested index and closes the dialog on Escape', async () => {
    await act(async () => {
      root.render(<PreviewProvider><Opener /></PreviewProvider>);
    });
    await flush();

    await act(async () => container.querySelector<HTMLButtonElement>('button')?.click());
    await flush();

    const dialog = container.querySelector('[role="dialog"]');
    expect(dialog?.textContent).toContain('второй.txt');
    expect(dialog?.textContent).toContain('2 / 2');

    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(container.querySelector('[role="dialog"]')).toBeNull();
  });

  it('does nothing for an empty file list', async () => {
    await act(async () => {
      root.render(<PreviewProvider><Opener /></PreviewProvider>);
    });
    await flush();

    await act(async () => container.querySelectorAll<HTMLButtonElement>('button')[1]?.click());
    await flush();
    expect(container.querySelector('[role="dialog"]')).toBeNull();
  });
});
