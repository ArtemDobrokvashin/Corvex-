import { describe, expect, it } from 'vitest';
import { isMailPrintShortcut } from './printShortcut';

const key = (overrides: Partial<Parameters<typeof isMailPrintShortcut>[0]> = {}) => ({
  key: 'p',
  code: 'KeyP',
  ctrlKey: false,
  metaKey: false,
  altKey: false,
  shiftKey: false,
  ...overrides,
});
const openMail = { hasActiveMail: true, composing: false, typing: false };

describe('isMailPrintShortcut', () => {
  it('перехватывает Ctrl+P и Cmd+P на открытом письме', () => {
    expect(isMailPrintShortcut(key({ ctrlKey: true }), openMail)).toBe(true);
    expect(isMailPrintShortcut(key({ metaKey: true }), openMail)).toBe(true);
    expect(isMailPrintShortcut(key({ key: 'P', ctrlKey: true }), openMail)).toBe(true);
    // Русская раскладка: нажата физическая P, но `key` — «з». Спасает `code`.
    expect(isMailPrintShortcut(key({ key: 'з', ctrlKey: true }), openMail)).toBe(true);
  });

  it('не перехватывает сочетание без открытого письма — печать страницы остаётся браузеру', () => {
    expect(isMailPrintShortcut(key({ ctrlKey: true }), { ...openMail, hasActiveMail: false })).toBe(false);
  });

  it('не перехватывает при открытом черновике и при фокусе в поле ввода', () => {
    expect(isMailPrintShortcut(key({ ctrlKey: true }), { ...openMail, composing: true })).toBe(false);
    expect(isMailPrintShortcut(key({ ctrlKey: true }), { ...openMail, typing: true })).toBe(false);
  });

  it('не трогает похожие сочетания: Alt, Shift, Ctrl+Shift+P и одиночный P', () => {
    expect(isMailPrintShortcut(key({ ctrlKey: true, altKey: true }), openMail)).toBe(false);
    expect(isMailPrintShortcut(key({ ctrlKey: true, shiftKey: true }), openMail)).toBe(false);
    expect(isMailPrintShortcut(key(), openMail)).toBe(false);
    expect(isMailPrintShortcut(key({ ctrlKey: true, key: 'o', code: 'KeyO' }), openMail)).toBe(false);
    // Чужая физическая клавиша не должна совпасть только из-за `code`.
    expect(isMailPrintShortcut(key({ ctrlKey: true, key: 'з', code: 'KeyZ' }), openMail)).toBe(false);
  });
});
