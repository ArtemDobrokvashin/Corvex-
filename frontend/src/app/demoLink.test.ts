import { describe, expect, it } from 'vitest';
import { isDemoRequested } from './demoLink';

describe('ссылка на витрину без сервера', () => {
  it('включает демо только при явном значении 1', () => {
    expect(isDemoRequested('?demo=1')).toBe(true);
    expect(isDemoRequested('?demo=0')).toBe(false);
    expect(isDemoRequested('?demo=true')).toBe(false);
    expect(isDemoRequested('?from=mail&demo=1')).toBe(true);
  });

  it('не срабатывает на обычных ссылках', () => {
    expect(isDemoRequested('')).toBe(false);
    expect(isDemoRequested('?date=2026-10-19')).toBe(false);
  });
});
