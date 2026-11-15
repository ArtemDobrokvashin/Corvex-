// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../i18n';
import { CalendarWidget } from './CalendarWidget';

describe('CalendarWidget', () => {
  let container: HTMLDivElement;
  let root: ReturnType<typeof createRoot>;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 10, 14, 12));
    useLocaleStore.setState({ locale: 'ru' });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.useRealTimers();
    useLocaleStore.setState({ locale: 'ru' });
  });

  it('renders calendar button and opens popover on click', () => {
    act(() => {
      root.render(<CalendarWidget />);
    });

    // Кнопка календаря должна быть видна
    const button = container.querySelector('button[aria-label="Календарь"]');
    expect(button).toBeDefined();

    // Поповер не должен быть виден до клика
    expect(container.querySelector('[role="dialog"]')).toBeNull();

    // Открываем поповер
    act(() => {
      button!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    // Поповер должен появиться
    const dialog = container.querySelector('[role="dialog"]');
    expect(dialog).toBeDefined();
    expect(dialog!.getAttribute('aria-modal')).toBeNull();
    expect(dialog!.querySelector('a')?.getAttribute('href')).toBe('/calendar');
  });

  it('shows current month in Russian', () => {
    act(() => {
      root.render(<CalendarWidget />);
    });

    const button = container.querySelector('button[aria-label="Календарь"]')!;
    act(() => {
      button.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const dialog = container.querySelector('[role="dialog"]')!;
    expect(dialog.textContent).toContain('ноябрь 2026');
    expect(dialog.textContent).toContain('14'); // сегодняшний день
  });

  it('shows current month in English', () => {
    useLocaleStore.setState({ locale: 'en' });
    act(() => {
      root.render(<CalendarWidget />);
    });

    const button = container.querySelector('button[aria-label="Calendar"]')!;
    act(() => {
      button.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const dialog = container.querySelector('[role="dialog"]')!;
    expect(dialog.textContent).toContain('November 2026');
    expect(Array.from(dialog.querySelectorAll('th')).map((el) => el.textContent)).toEqual(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']);
  });

  it('switches months within popover', () => {
    act(() => {
      root.render(<CalendarWidget />);
    });

    const button = container.querySelector('button[aria-label="Календарь"]')!;
    act(() => {
      button.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const dialog = container.querySelector('[role="dialog"]')!;
    expect(dialog.textContent).toContain('ноябрь 2026');

    // Нажимаем "следующий месяц"
    const nextButton = Array.from(dialog.querySelectorAll('button')).find(
      (b) => b.getAttribute('aria-label') === 'Следующий месяц',
    );
    expect(nextButton).toBeDefined();

    act(() => {
      nextButton!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    expect(dialog.textContent).toContain('декабрь 2026');
  });

  it('highlights today', () => {
    act(() => {
      root.render(<CalendarWidget />);
    });

    const button = container.querySelector('button[aria-label="Календарь"]')!;
    act(() => {
      button.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const dialog = container.querySelector('[role="dialog"]')!;
    const cells = dialog.querySelectorAll('td');
    let todayFound = false;
    cells.forEach((cell) => {
      if (cell.textContent === '14' && cell.className.includes('bg-accent-soft')) {
        todayFound = true;
      }
    });
    expect(todayFound).toBe(true);
  });
  it('places Monday first and November 1 in the Sunday column', () => {
    act(() => root.render(<CalendarWidget />));
    const button = container.querySelector('button[aria-label="Календарь"]')!;
    act(() => button.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    const headings = Array.from(container.querySelectorAll('th')).map((el) => el.textContent?.toLowerCase());
    expect(headings).toEqual(['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс']);
    const firstWeek = container.querySelectorAll('tbody tr')[0].querySelectorAll('td');
    expect(firstWeek[6].textContent).toBe('1');
  });

  it('closes on Escape and restores focus to the trigger', () => {
    act(() => root.render(<CalendarWidget />));
    const button = container.querySelector<HTMLButtonElement>('button[aria-label="Календарь"]')!;
    act(() => button.click());
    act(() => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })));
    expect(container.querySelector('[role="dialog"]')).toBeNull();
    expect(document.activeElement).toBe(button);
  });

  it('closes after clicking outside and translates the link in English', () => {
    useLocaleStore.setState({ locale: 'en' });
    act(() => root.render(<CalendarWidget />));
    const button = container.querySelector<HTMLButtonElement>('button[aria-label="Calendar"]')!;
    act(() => button.click());
    expect(container.querySelector('[role="dialog"] a')?.textContent).toBe('Open calendar');
    act(() => document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })));
    expect(container.querySelector('[role="dialog"]')).toBeNull();
  });

});
