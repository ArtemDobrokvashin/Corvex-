import { describe, expect, it } from 'vitest';
import {
  countLabel,
  formatBytes,
  formatDate,
  localizeApiDetail,
  plural,
  systemGroupName,
  translate,
} from './index';

describe('RU/EN localization', () => {
  it('translates interface strings and interpolates values', () => {
    expect(translate('ru', 'Почта')).toBe('Почта');
    expect(translate('en', 'Почта')).toBe('Mail');
    expect(translate('en', '{current} из {total}', { current: 2, total: 9 })).toBe('2 of 9');
  });

  it('localizes canonical API errors, dynamic patterns and prefixes', () => {
    expect(localizeApiDetail('Неверный логин или пароль', 'en')).toBe('Incorrect username or password');
    expect(localizeApiDetail('Слишком много неверных попыток. Повторите через 15 минут', 'en'))
      .toBe('Too many failed attempts. Try again in 15 minutes');
    expect(localizeApiDetail('Логин уже занят: i.ivanov', 'en')).toBe('This username is already taken: i.ivanov');
    expect(localizeApiDetail('username: укажите логин', 'en')).toBe('username: enter a username');
  });

  it('localizes English Bean Validation defaults back to Russian', () => {
    expect(localizeApiDetail('username: must not be blank', 'ru')).toBe('username: не должно быть пустым');
    expect(localizeApiDetail('quotaBytes: must be greater than or equal to 0', 'ru'))
      .toBe('quotaBytes: должно быть не меньше 0');
  });

  it('formats dates, bytes, plurals and system groups for both locales', () => {
    expect(formatDate('en', '2026-10-19T12:00:00Z')).toContain('Oct 19, 2026');
    expect(formatDate('ru', '2026-10-19T12:00:00Z')).toContain('19');
    expect(formatBytes('en', 5 * 1024 ** 3)).toBe('5.0 GB');
    expect(formatBytes('ru', 5 * 1024 ** 3)).toBe('5,0 ГБ');

    expect(plural('ru', 1, ['группа', 'группы', 'групп'], ['group', 'groups'])).toBe('группа');
    expect(plural('ru', 3, ['группа', 'группы', 'групп'], ['group', 'groups'])).toBe('группы');
    expect(plural('ru', 12, ['группа', 'группы', 'групп'], ['group', 'groups'])).toBe('групп');
    expect(countLabel('en', 1, ['группа', 'группы', 'групп'], ['group', 'groups'])).toBe('1 group');
    expect(countLabel('en', 2, ['группа', 'группы', 'групп'], ['group', 'groups'])).toBe('2 groups');

    expect(systemGroupName('ru', 'ACCOUNTANT', 'Stored name')).toBe('Бухгалтер');
    expect(systemGroupName('en', 'ACCOUNTANT', 'Бухгалтер')).toBe('Accountant');
    expect(systemGroupName('en', 'SALES', 'Sales')).toBe('Sales');
  });
});
