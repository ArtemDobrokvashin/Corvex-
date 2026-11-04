import { describe, expect, it } from 'vitest';
import { BYTES_PER_GIB, quotaBytesToGigabytes, quotaGigabytesToBytes, quotaUsagePercent } from './quota';

describe('disk quota values', () => {
  it('converts whole, decimal and comma-separated GB to bytes', () => {
    expect(quotaGigabytesToBytes('0')).toBe(0);
    expect(quotaGigabytesToBytes('2.5')).toBe(2.5 * BYTES_PER_GIB);
    expect(quotaGigabytesToBytes(' 1,25 ')).toBe(1.25 * BYTES_PER_GIB);
  });

  it('rejects blank, negative and unsafe values instead of sending them to the API', () => {
    expect(quotaGigabytesToBytes('')).toBeNull();
    expect(quotaGigabytesToBytes('-1')).toBeNull();
    expect(quotaGigabytesToBytes('abc')).toBeNull();
    expect(quotaGigabytesToBytes('1e100')).toBeNull();
    expect(quotaGigabytesToBytes(String(Number.MAX_SAFE_INTEGER))).toBeNull();
  });

  it('round-trips the initial input without silently changing an existing quota', () => {
    for (const bytes of [0, 1, 5 * BYTES_PER_GIB, 5 * BYTES_PER_GIB + 123]) {
      expect(quotaGigabytesToBytes(quotaBytesToGigabytes(bytes))).toBe(bytes);
    }
  });

  it('distinguishes unlimited quota and exposes over-100-percent usage', () => {
    expect(quotaUsagePercent(null, 100)).toBeNull();
    expect(quotaUsagePercent(100, 0)).toBeNull();
    expect(quotaUsagePercent(0, 100)).toBe(0);
    expect(quotaUsagePercent(150, 100)).toBe(150);
  });
});
