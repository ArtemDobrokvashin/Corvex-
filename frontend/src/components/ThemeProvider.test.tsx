// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useUIStore } from '../store/uiStore';
import { ThemeProvider } from './ThemeProvider';

function renderProvider() {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(<ThemeProvider />);
  });
  return { container, root };
}

describe('ThemeProvider', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    // Сброс стора UI между тестами
    useUIStore.setState({ theme: 'light', accent: 'indigo', density: 'compact' });
  });

  afterEach(async () => {
    vi.useRealTimers();
    useUIStore.setState({ theme: 'light', accent: 'indigo', density: 'compact' });
  });

  it('applies data-theme="light" by default', () => {
    const { root, container } = renderProvider();
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    act(() => root.unmount());
    container.remove();
  });

  it('applies data-theme="dark" when store says dark', () => {
    useUIStore.setState({ theme: 'dark' });
    const { root, container } = renderProvider();
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    act(() => root.unmount());
    container.remove();
  });

  it('writes accent color value (not key) into --corvex-accent', () => {
    useUIStore.setState({ accent: 'rose' });
    const { root, container } = renderProvider();
    expect(document.documentElement.style.getPropertyValue('--corvex-accent').trim()).toBe(
      '#C0265A',
    );
    act(() => root.unmount());
    container.remove();
  });

  it('applies data-density from store', () => {
    useUIStore.setState({ density: 'comfortable' });
    const { root, container } = renderProvider();
    expect(document.documentElement.getAttribute('data-density')).toBe('comfortable');
    act(() => root.unmount());
    container.remove();
  });

  it('system theme follows prefers-color-scheme dark', () => {
    useUIStore.setState({ theme: 'system' });
    const media = {
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    };
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: vi.fn().mockImplementation(() => media),
    });

    const { root, container } = renderProvider();
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');

    act(() => root.unmount());
    container.remove();
    vi.restoreAllMocks();
  });

  it('system theme follows prefers-color-scheme light', () => {
    useUIStore.setState({ theme: 'system' });
    const media = {
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    };
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: vi.fn().mockImplementation(() => media),
    });

    const { root, container } = renderProvider();
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');

    act(() => root.unmount());
    container.remove();
    vi.restoreAllMocks();
  });

  it('dark theme sets --corvex-accent CSS variable on html', () => {
    useUIStore.setState({ theme: 'dark', accent: 'emerald' });
    const { root, container } = renderProvider();
    expect(document.documentElement.style.getPropertyValue('--corvex-accent').trim()).toBe(
      '#16835F',
    );
    act(() => root.unmount());
    container.remove();
  });

  it('applies green with readable text and retains it across theme changes', () => {
    useUIStore.setState({ theme: 'light', accent: 'green' });
    const { root, container } = renderProvider();
    expect(document.documentElement.style.getPropertyValue('--corvex-accent')).toBe('#10B981');
    expect(document.documentElement.style.getPropertyValue('--corvex-accent-on-light')).toBe('#056746');
    expect(document.documentElement.style.getPropertyValue('--corvex-accent-button-text')).toBe('#092E20');
    act(() => useUIStore.setState({ theme: 'dark' }));
    expect(document.documentElement.style.getPropertyValue('--corvex-accent-on-dark')).toBe('#8BE2BD');
    act(() => root.unmount());
    container.remove();
  });

  it('uses a readable dark-mode accent for each selected colour', () => {
    useUIStore.setState({ theme: 'dark', accent: 'rose' });
    const { root, container } = renderProvider();
    expect(document.documentElement.style.getPropertyValue('--corvex-accent-on-dark')).toBe('#FFABCA');
    act(() => root.unmount());
    container.remove();
  });

  it('dark theme applies dark background to body and root', () => {
    useUIStore.setState({ theme: 'dark' });
    const { root, container } = renderProvider();
    // data-theme на html должен быть 'dark'
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    act(() => root.unmount());
    container.remove();
  });

  it('light theme applies light background to body and root', () => {
    useUIStore.setState({ theme: 'light' });
    const { root, container } = renderProvider();
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    act(() => root.unmount());
    container.remove();
  });
});
