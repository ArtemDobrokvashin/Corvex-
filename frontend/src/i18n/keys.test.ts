import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ENGLISH_TRANSLATIONS } from './index';

const SRC = resolve(__dirname, '..');

function sourceFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) sourceFiles(path, acc);
    else if (/\.(ts|tsx)$/.test(entry.name) && !/\.test\./.test(entry.name)) acc.push(path);
  }
  return acc;
}

/**
 * `t('русский текст')` без записи в словаре молча показывает русский текст
 * в английском интерфейсе. Это дефект, который виден только живьём, поэтому
 * список ключей проверяется тестом, а не глазами.
 */
/** Сравниваются значения после компиляции, а не исходный текст литералов. */
function unescapeLiteral(value: string): string {
  return value.replace(/\\(['\\])/g, '$1');
}

function missingKeys(): string[] {
  const missing = new Set<string>();
  for (const file of sourceFiles(SRC)) {
    if (file === join(SRC, 'i18n', 'index.tsx')) continue;
    const text = readFileSync(file, 'utf8');
    for (const match of text.matchAll(/\bt\(\s*'((?:[^'\\]|\\.)*)'/g)) {
      const key = unescapeLiteral(match[1]);
      if (!/[A-Za-zА-Яа-яЁё]/.test(key)) continue;
      if (!(key in ENGLISH_TRANSLATIONS)) missing.add(`${key}  (${file.slice(SRC.length + 1)})`);
    }
  }
  return [...missing].sort();
}

describe('translation coverage', () => {
  it('has an English word for every literal interface key', () => {
    expect(missingKeys()).toEqual([]);
  });

  it('does not translate mail settings and bulk actions into empty strings', () => {
    for (const key of ['Настройки почты', 'Подпись', 'Групповые действия', 'Переслать выбранные']) {
      expect(ENGLISH_TRANSLATIONS[key]?.trim()).toBeTruthy();
      expect(ENGLISH_TRANSLATIONS[key]).not.toBe(key);
    }
  });
});
