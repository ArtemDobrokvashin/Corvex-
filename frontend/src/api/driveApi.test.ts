import { describe, expect, it } from 'vitest';
import { absoluteShareUrl, formatBytes } from './driveApi';

describe('диск: размеры и ссылки', () => {
  it('считает размер в десятичных единицах с локальным разделителем', () => {
    expect(formatBytes(0, 'ru')).toBe('0 Б');
    expect(formatBytes(950, 'ru')).toBe('950 Б');
    expect(formatBytes(2048, 'ru')).toBe('2,0 КБ');
    expect(formatBytes(2048, 'en')).toBe('2.0 KB');
    expect(formatBytes(5_000_000_000, 'ru')).toBe('5,0 ГБ');
  });

  it('не показывает мусор при отрицательных и нечисловых значениях', () => {
    expect(formatBytes(Number.NaN, 'en')).toBe('0 B');
    expect(formatBytes(-10, 'ru')).toBe('0 Б');
  });

  it('строит абсолютный адрес ссылки от origin сайта', () => {
    expect(absoluteShareUrl('/api/v1/drive/shared/token', 'https://mail.example.ru'))
      .toBe('https://mail.example.ru/api/v1/drive/shared/token');
  });
});
