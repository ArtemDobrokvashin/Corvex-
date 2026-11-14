// @vitest-environment jsdom
process.env.TZ = 'Europe/Moscow';
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../i18n';
import { Clock } from './Clock';

describe('Clock', () => {
  let container: HTMLDivElement;
  let root: ReturnType<typeof createRoot>;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'ru' });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-14T15:30:00Z'));
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.useRealTimers();
    useLocaleStore.setState({ locale: 'ru' });
  });

  it('shows local time without seconds in Russian', async () => {
    await act(async () => {
      root.render(<Clock />);
    });
    // 15:30 UTC = 18:30 в Москве (UTC+3 в ноябре)
    expect(container.textContent).toContain('18:30');
    // Часы без секунд: только один двоеточа между часами и минутами
    const colons = (container.textContent ?? '').split(':').length - 1;
    expect(colons).toBe(1);
  });

  it('shows local time in English', async () => {
    useLocaleStore.setState({ locale: 'en' });
    await act(async () => {
      root.render(<Clock />);
    });
    expect(container.textContent).toContain('6:30 PM');
  });

  it('updates time when the timer fires', async () => {
    await act(async () => {
      root.render(<Clock />);
    });
    expect(container.textContent).toContain('18:30');

    await act(async () => {
      vi.advanceTimersByTime(60_000);
    });
    expect(container.textContent).toContain('18:31');
  });

  it('clears the interval timer on unmount', async () => {
    const clearIntervalSpy = vi.spyOn(globalThis, 'clearInterval');
    await act(async () => {
      root.render(<Clock />);
    });
    await act(async () => {
      root.unmount();
    });
    expect(clearIntervalSpy).toHaveBeenCalled();
    clearIntervalSpy.mockRestore();
  });
});
