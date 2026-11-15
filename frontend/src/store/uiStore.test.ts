// @vitest-environment jsdom
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { createUIStore, sanitizePersisted } from './uiStore';

const TEST_STORAGE_KEY = 'corvex.ui.test';

function createTestStore() {
  return createUIStore(TEST_STORAGE_KEY);
}

function writeTestStorage(value: unknown) {
  window.localStorage.setItem(TEST_STORAGE_KEY, JSON.stringify(value));
}

function clearTestStorage() {
  window.localStorage.removeItem(TEST_STORAGE_KEY);
}

describe('uiStore hydration validation', () => {
  beforeEach(() => {
    clearTestStorage();
  });

  afterEach(() => {
    clearTestStorage();
  });

  it('sanitizePersisted returns empty object for non-object input', () => {
    expect(sanitizePersisted(null)).toEqual({});
    expect(sanitizePersisted(undefined)).toEqual({});
    expect(sanitizePersisted('string')).toEqual({});
    expect(sanitizePersisted(42)).toEqual({});
  });

  it('sanitizePersisted keeps only known valid values', () => {
    const result = sanitizePersisted({
      theme: 'dark',
      accent: 'emerald',
      density: 'comfortable',
      unknownField: 'ignored',
    });
    expect(result).toEqual({ theme: 'dark', accent: 'emerald', density: 'comfortable' });
  });

  it('sanitizePersisted drops invalid theme', () => {
    const result = sanitizePersisted({ theme: 'neon' as any });
    expect(result.theme).toBeUndefined();
  });

  it('sanitizePersisted drops invalid accent', () => {
    const result = sanitizePersisted({ accent: 'neon' as any });
    expect(result.accent).toBeUndefined();
  });

  it('sanitizePersisted drops invalid density', () => {
    const result = sanitizePersisted({ density: 'huge' as any });
    expect(result.density).toBeUndefined();
  });

  it('restores valid settings from localStorage on new store instance', () => {
    writeTestStorage({ theme: 'dark', accent: 'rose', density: 'comfortable' });
    const store = createTestStore();
    expect(store.getState().theme).toBe('dark');
    expect(store.getState().accent).toBe('rose');
    expect(store.getState().density).toBe('comfortable');
  });

  it('falls back to defaults when localStorage contains invalid JSON', () => {
    window.localStorage.setItem(TEST_STORAGE_KEY, '{not valid json');
    const store = createTestStore();
    expect(store.getState().theme).toBe('light');
    expect(store.getState().accent).toBe('indigo');
    expect(store.getState().density).toBe('compact');
  });

  it('falls back to defaults when localStorage contains unknown theme', () => {
    writeTestStorage({ theme: 'invalid-theme' as any, accent: 'indigo', density: 'compact' });
    const store = createTestStore();
    expect(store.getState().theme).toBe('light');
    expect(store.getState().accent).toBe('indigo');
    expect(store.getState().density).toBe('compact');
  });

  it('falls back to defaults when localStorage contains unknown accent', () => {
    writeTestStorage({ theme: 'light', accent: 'invalid-accent' as any, density: 'compact' });
    const store = createTestStore();
    expect(store.getState().theme).toBe('light');
    expect(store.getState().accent).toBe('indigo');
    expect(store.getState().density).toBe('compact');
  });

  it('falls back to defaults when localStorage contains unknown density', () => {
    writeTestStorage({ theme: 'light', accent: 'indigo', density: 'invalid-density' as any });
    const store = createTestStore();
    expect(store.getState().theme).toBe('light');
    expect(store.getState().accent).toBe('indigo');
    expect(store.getState().density).toBe('compact');
  });

  it('preserves valid fields when some fields are invalid', () => {
    writeTestStorage({ theme: 'dark', accent: 'invalid-accent' as any, density: 'comfortable' });
    const store = createTestStore();
    expect(store.getState().theme).toBe('dark');
    expect(store.getState().accent).toBe('indigo');
    expect(store.getState().density).toBe('comfortable');
  });

  it('setters update state', () => {
    const store = createTestStore();
    store.setState({ theme: 'dark' });
    expect(store.getState().theme).toBe('dark');

    store.setState({ accent: 'emerald' });
    expect(store.getState().accent).toBe('emerald');

    store.setState({ density: 'comfortable' });
    expect(store.getState().density).toBe('comfortable');
  });

  it('keeps working in memory when browser storage refuses writes', () => {
    const store = createTestStore();
    const blocked = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    try {
      expect(() => store.getState().setAccent('rose')).not.toThrow();
      expect(store.getState().accent).toBe('rose');
    } finally { blocked.mockRestore(); }
  });

  it('setters persist only UI fields (no setters in localStorage)', () => {
    const store = createTestStore();
    store.setState({ theme: 'dark' });

    const raw = window.localStorage.getItem(TEST_STORAGE_KEY);
    expect(raw).not.toBeNull();
    const parsed = JSON.parse(raw!);
    expect(parsed.state.theme).toBe('dark');
    expect(parsed.state.setTheme).toBeUndefined();
    expect(parsed.state.setAccent).toBeUndefined();
    expect(parsed.state.setDensity).toBeUndefined();
  });
});
