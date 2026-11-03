import { describe, expect, it } from 'vitest';
import { displayMetric, sharePercent, summarizeHealth } from './adminInsights';

describe('admin health and analytics presentation', () => {
  it('counts every non-UP service as requiring attention', () => {
    expect(summarizeHealth([
      { name: 'auth', status: 'UP', version: null },
      { name: 'search', status: 'DEGRADED', version: null },
      { name: 'mail', status: 'DOWN', version: null },
    ])).toEqual({ total: 3, up: 1, issues: 2 });
  });

  it('handles an empty health response', () => {
    expect(summarizeHealth([])).toEqual({ total: 0, up: 0, issues: 0 });
  });

  it('produces bounded chart shares without NaN for empty totals', () => {
    expect(sharePercent(25, 100)).toBe(25);
    expect(sharePercent(1, 3)).toBe(33);
    expect(sharePercent(10, 0)).toBe(0);
    expect(sharePercent(-1, 10)).toBe(0);
    expect(sharePercent(20, 10)).toBe(100);
    expect(sharePercent(Number.NaN, 10)).toBe(0);
  });

  it('distinguishes unavailable login metrics from a real zero', () => {
    expect(displayMetric(null)).toBeNull();
    expect(displayMetric(-1)).toBeNull();
    expect(displayMetric(Number.NaN)).toBeNull();
    expect(displayMetric(0)).toBe(0);
    expect(displayMetric(12)).toBe(12);
  });
});
