import { describe, expect, it } from 'vitest';
import { decodeTextBlob } from './previewText';

describe('decodeTextBlob', () => {
  it('decodes UTF-8 with emoji', async () => {
    await expect(decodeTextBlob(new Blob(['Привет, 🌍']))).resolves.toBe('Привет, 🌍');
  });

  it('falls back to windows-1251 for legacy mail attachments', async () => {
    // "Привет" в windows-1251.
    const bytes = new Uint8Array([0xcf, 0xf0, 0xe8, 0xe2, 0xe5, 0xf2]);
    await expect(decodeTextBlob(new Blob([bytes]))).resolves.toBe('Привет');
  });

  it('honours UTF-16LE and UTF-16BE byte order marks', async () => {
    const little = new Uint8Array([0xff, 0xfe, 0x41, 0x00, 0x42, 0x00]);
    const big = new Uint8Array([0xfe, 0xff, 0x00, 0x41, 0x00, 0x42]);
    await expect(decodeTextBlob(new Blob([little]))).resolves.toBe('AB');
    await expect(decodeTextBlob(new Blob([big]))).resolves.toBe('AB');
  });

  it('returns an empty string for an empty file', async () => {
    await expect(decodeTextBlob(new Blob([]))).resolves.toBe('');
  });
});
