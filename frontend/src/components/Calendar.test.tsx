// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../i18n';
import { Calendar } from './Calendar';

describe('Calendar', () => {
  let container: HTMLDivElement;
  let root: ReturnType<typeof createRoot>;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'ru' });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-14T12:00:00Z'));
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.useRealTimers();
    useLocaleStore.setState({ locale: 'ru' });
  });

  it('renders the current month and year in Russian', async () => {
    await act(async () => {
      root.render(<Calendar />);
    });
    expect(container.textContent).toContain('Календарь');
    expect(container.textContent).toContain('ноябрь 2026');
    // сегодня 14-е выделено
    expect(container.textContent).toContain('14');
  });

  it('switches to previous month and crosses the year boundary', async () => {
    await act(async () => {
      root.render(<Calendar />);
    });
    expect(container.textContent).toContain('ноябрь 2026');

    const prevButton = Array.from(container.querySelectorAll('button')).find((b) =>
      b.ariaLabel?.includes('Предыдущий') || b.ariaLabel?.includes('Previous'),
    );
    expect(prevButton).toBeDefined();

    await act(async () => {
      prevButton!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(container.textContent).toContain('октябрь 2026');
  });

  it('switches from January to December of the previous year', async () => {
    vi.setSystemTime(new Date('2026-01-15T12:00:00Z'));
    // Пересоздаём компонент с новой датой
    await act(async () => {
      root.unmount();
      root = createRoot(container);
      root.render(<Calendar />);
    });
    expect(container.textContent).toContain('январь 2026');

    const prevButton = Array.from(container.querySelectorAll('button')).find((b) =>
      b.ariaLabel?.includes('Предыдущий') || b.ariaLabel?.includes('Previous'),
    );
    await act(async () => {
      prevButton!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(container.textContent).toContain('декабрь 2025');
  });

  it('switches from December to January of the next year', async () => {
    vi.setSystemTime(new Date('2026-12-20T12:00:00Z'));
    await act(async () => {
      root.unmount();
      root = createRoot(container);
      root.render(<Calendar />);
    });
    expect(container.textContent).toContain('декабрь 2026');

    const nextButton = Array.from(container.querySelectorAll('button')).find((b) =>
      b.ariaLabel?.includes('Следующий') || b.ariaLabel?.includes('Next'),
    );
    await act(async () => {
      nextButton!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(container.textContent).toContain('январь 2027');
  });

  it('does not claim server-side storage or notifications', async () => {
    await act(async () => {
      root.render(<Calendar />);
    });
    const text = container.textContent ?? '';
    expect(text).not.toContain('уведом');
    expect(text).not.toContain('server');
    expect(text).not.toContain('API');
    expect(text).not.toContain('Сервер');
    expect(text).not.toContain('уведомления');
  });

  it('highlights today', async () => {
    // Сегодня 14 ноября 2026
    await act(async () => {
      root.render(<Calendar />);
    });
    const cells = container.querySelectorAll('td');
    let todayFound = false;
    cells.forEach((cell) => {
      if (cell.textContent === '14' && cell.className.includes('bg-accent-soft')) {
        todayFound = true;
      }
    });
    expect(todayFound).toBe(true);
  });
});
