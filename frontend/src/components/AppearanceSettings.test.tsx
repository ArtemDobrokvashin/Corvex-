// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useUIStore, type UIState } from '../store/uiStore';
import { AppearanceSettings } from './AppearanceSettings';
import { useLocaleStore } from '../i18n';

const STORAGE_KEY = 'corvex.ui';

function renderSettings() {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(<AppearanceSettings />);
  });
  return { container, root };
}

function clearStorage() {
  try {
    window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}

function setState(state: Partial<import('../store/uiStore').UIState>) {
  act(() => {
    useUIStore.setState(state);
  });
}

describe('AppearanceSettings', () => {
  beforeEach(() => {
    clearStorage();
    useLocaleStore.setState({ locale: 'ru' });
    setState({ theme: 'light', accent: 'indigo', density: 'compact' });
  });

  afterEach(() => {
    clearStorage();
    useLocaleStore.setState({ locale: 'ru' });
    setState({ theme: 'light', accent: 'indigo', density: 'compact' });
  });

  it('renders theme, accent and density sections', () => {
    const { container, root } = renderSettings();
    expect(container.textContent).toContain('Внешний вид');
    expect(container.textContent).toContain('Тема оформления');
    expect(container.textContent).toContain('Акцентный цвет');
    expect(container.textContent).toContain('Плотность элементов');
    act(() => root.unmount());
    container.remove();
  });

  it('switches theme via buttons', () => {
    const { container, root } = renderSettings();
    expect(useUIStore.getState().theme).toBe('light');

    const darkButton = Array.from(container.querySelectorAll('button')).find(
      (b) => b.textContent?.includes('Тёмная'),
    );
    expect(darkButton).toBeDefined();

    act(() => {
      darkButton!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(useUIStore.getState().theme).toBe('dark');

    act(() => root.unmount());
    container.remove();
  });

  it('switches accent color via buttons', () => {
    const { container, root } = renderSettings();
    expect(useUIStore.getState().accent).toBe('indigo');

    // Кнопка акцента "Розовый": внутренний span имеет inline-style с background-color #C0265A (rgb в jsdom)
    const roseButton = Array.from(container.querySelectorAll('button')).find((b) => {
      const spans = b.querySelectorAll('span');
      return Array.from(spans).some((s) => {
        const style = (s as HTMLElement).style.backgroundColor;
        return style === '#C0265A' || style === 'rgb(192, 38, 90)';
      });
    });
    expect(roseButton).toBeDefined();

    act(() => {
      roseButton!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(useUIStore.getState().accent).toBe('rose');

    act(() => root.unmount());
    container.remove();
  });

  it('switches density via buttons', () => {
    const { container, root } = renderSettings();
    expect(useUIStore.getState().density).toBe('compact');

    const comfortableButton = Array.from(container.querySelectorAll('button')).find(
      (b) => b.textContent?.includes('Свободная'),
    );
    expect(comfortableButton).toBeDefined();

    act(() => {
      comfortableButton!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(useUIStore.getState().density).toBe('comfortable');

    act(() => root.unmount());
    container.remove();
  });

  it('reset button restores defaults', () => {
    const { container, root } = renderSettings();

    setState({ theme: 'dark', accent: 'rose', density: 'comfortable' });
    expect(useUIStore.getState().theme).toBe('dark');

    const resetButton = Array.from(container.querySelectorAll('button')).find(
      (b) => b.getAttribute('aria-label') === 'Сбросить настройки внешнего вида',
    );
    expect(resetButton).toBeDefined();

    act(() => {
      resetButton!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(useUIStore.getState().theme).toBe('light');
    expect(useUIStore.getState().accent).toBe('indigo');
    expect(useUIStore.getState().density).toBe('compact');

    act(() => root.unmount());
    container.remove();
  });

  it('persists settings to localStorage and restores them', () => {
    const { root, container } = renderSettings();

    setState({ theme: 'dark', accent: 'emerald', density: 'comfortable' });

    act(() => root.unmount());
    container.remove();

    // Новое монтирование — должно восстановить из localStorage
    const { root: root2, container: container2 } = renderSettings();
    expect(useUIStore.getState().theme).toBe('dark');
    expect(useUIStore.getState().accent).toBe('emerald');
    expect(useUIStore.getState().density).toBe('comfortable');

    act(() => root2.unmount());
    container2.remove();
  });

  it('ignores corrupted localStorage and falls back to defaults', () => {
    try {
      window.localStorage.setItem(STORAGE_KEY, '{not valid json');
    } catch {
      // ignore
    }

    const { root, container } = renderSettings();
    expect(useUIStore.getState().theme).toBe('light');
    expect(useUIStore.getState().accent).toBe('indigo');
    expect(useUIStore.getState().density).toBe('compact');

    act(() => root.unmount());
    container.remove();
  });

  it('sections do not use the field container class (fixed height)', () => {
    const { container, root } = renderSettings();

    // Три секции (Тема, Акцентный цвет, Плотность) не должны использовать класс .field,
    // у которого фиксированная высота h-11 (2.75rem) — это неправильный контейнер для секций.
    const sectionContainers = container.querySelectorAll('.space-y-3');
    expect(sectionContainers.length).toBeGreaterThanOrEqual(3);

    sectionContainers.forEach((el) => {
      expect(el.className).not.toContain('field');
    });

    act(() => root.unmount());
    container.remove();
  });

  it('accent color buttons have accessible aria-label names', () => {
    const { container, root } = renderSettings();

    const colorButtons = Array.from(container.querySelectorAll('button[role="radio"]')).filter(
      (b) => b.getAttribute('aria-label') !== null,
    );
    expect(colorButtons.length).toBeGreaterThan(0);

    colorButtons.forEach((b) => {
      expect(b.getAttribute('aria-label')).not.toBe('');
      expect(b.getAttribute('aria-label')).not.toBe('indigo');
      expect(b.getAttribute('aria-label')).not.toBe('rose');
      expect(b.getAttribute('aria-label')).not.toBe('emerald');
      expect(b.getAttribute('aria-label')).not.toBe('amber');
      expect(b.getAttribute('aria-label')).not.toBe('slate');
    });

    act(() => root.unmount());
    container.remove();
  });

  it('renders in English locale', () => {
    useLocaleStore.setState({ locale: 'en' });
    const { container, root } = renderSettings();

    expect(container.textContent).toContain('Appearance');
    expect(container.textContent).toContain('Theme');
    expect(container.textContent).toContain('Light');
    expect(container.textContent).toContain('Dark');
    expect(container.textContent).toContain('System');
    expect(container.textContent).toContain('Accent color');
    expect(container.textContent).toContain('Density');

    act(() => root.unmount());
    container.remove();
  });
});
