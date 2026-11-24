// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastProvider, useToast } from './ToastProvider';
import { useLocaleStore } from '../i18n';

/**
 * Слой уведомлений — основа P0-1 и P1-8: «Отменить» и итог массовой операции
 * должны жить в одном месте. Проверяем именно то, на что опирается интерфейс:
 * замену по ключу, действие, паузу и ограничение очереди.
 */
let host: HTMLDivElement;
let root: Root;
let api: ReturnType<typeof useToast>;

function Harness() {
  api = useToast();
  return null;
}

async function render() {
  await act(async () => {
    root.render(
      <ToastProvider>
        <Harness />
      </ToastProvider>,
    );
  });
}

function messages() {
  return [...host.querySelectorAll('[role="region"] p')].map((node) => node.textContent);
}

describe('toast layer', () => {
  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
    vi.useRealTimers();
  });

  it('shows a message with an action and runs it once', async () => {
    await render();
    const onSelect = vi.fn();
    await act(async () => { api.notify({ message: 'Message moved to Archive.', action: { label: 'Undo', onSelect } }); });
    expect(messages()).toContain('Message moved to Archive.');
    const undo = [...host.querySelectorAll('button')].find((button) => button.textContent === 'Undo')!;
    expect(undo).toBeTruthy();
    await act(async () => { undo.click(); await Promise.resolve(); });
    expect(onSelect).toHaveBeenCalledOnce();
    // Действие закрывает сообщение: повторно нажать «Отменить» нельзя.
    expect(messages()).not.toContain('Message moved to Archive.');
  });

  it('replaces a message with the same key instead of stacking duplicates', async () => {
    await render();
    await act(async () => {
      api.notify({ key: 'move-m1', message: 'First move' });
      api.notify({ key: 'move-m1', message: 'Second move' });
    });
    expect(messages()).toEqual(['Second move']);
  });

  it('keeps at most four messages and drops the oldest', async () => {
    await render();
    await act(async () => {
      for (let index = 0; index < 6; index += 1) api.notify({ message: `Message ${index}` });
    });
    expect(messages()).toEqual(['Message 2', 'Message 3', 'Message 4', 'Message 5']);
  });

  it('does not auto-close while the pointer rests on the message', async () => {
    vi.useFakeTimers();
    await render();
    await act(async () => { api.notify({ message: 'Undo me', durationMs: 1000 }); });
    const bubble = [...host.querySelectorAll('[role="region"] div')]
      .find((node) => node.className.includes('pointer-events-auto'))!;

    // React синтезирует enter/leave из mouseover/mouseout, а не из нативных
    // mouseenter/mouseleave: событие должно всплывать.
    await act(async () => { bubble.dispatchEvent(new MouseEvent('mouseover', { bubbles: true })); });
    await act(async () => { vi.advanceTimersByTime(5000); });
    // Курсор на сообщении — таймер снят, «Отменить» не исчезает из-под руки.
    expect(messages()).toContain('Undo me');
    await act(async () => { bubble.dispatchEvent(new MouseEvent('mouseout', { bubbles: true })); });
    await act(async () => { vi.advanceTimersByTime(1000); });
    expect(messages()).not.toContain('Undo me');
  });

  it('is a no-op without a provider so screens do not crash', async () => {
    function Bare() {
      const { notify } = useToast();
      notify({ message: 'ignored' });
      return <p>ok</p>;
    }
    await act(async () => { root.render(<Bare />); });
    expect(host.textContent).toBe('ok');
  });
});
