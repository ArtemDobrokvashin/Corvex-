export const BYTES_PER_GIB = 1024 ** 3;

/** Десятичный ввод ГиБ → целые bytes для long API; null означает невалидный ввод. */
export function quotaGigabytesToBytes(value: string): number | null {
  const normalized = value.trim().replace(',', '.');
  if (!/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(normalized)) return null;
  const gigabytes = Number(normalized);
  const bytes = Math.round(gigabytes * BYTES_PER_GIB);
  if (!Number.isFinite(bytes) || bytes < 0 || !Number.isSafeInteger(bytes)) return null;
  return bytes;
}

/** Девять знаков сохраняют точность до одного byte при обратном преобразовании. */
export function quotaBytesToGigabytes(bytes: number): string {
  if (!Number.isSafeInteger(bytes) || bytes <= 0) return '0';
  return (bytes / BYTES_PER_GIB).toFixed(9).replace(/\.?0+$/, '');
}

export function quotaUsagePercent(usedBytes: number, quotaBytes: number): number | null {
  if (quotaBytes <= 0) return null;
  if (usedBytes <= 0) return 0;
  return Math.round((usedBytes / quotaBytes) * 100);
}
