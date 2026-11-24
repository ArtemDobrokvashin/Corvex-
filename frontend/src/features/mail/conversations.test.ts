import { describe, expect, it } from 'vitest';
import type { DemoMail } from '../../api/demoData';
import {
  conversationAddress,
  conversationCountLabel,
  conversationKey,
  conversationSubject,
  groupIntoConversations,
  stripSubjectPrefixes,
} from './conversations';

function mail(overrides: Partial<DemoMail> & Pick<DemoMail, 'id'>): DemoMail {
  return {
    from: 'Alice <alice@example.com>',
    subject: 'Бюджет',
    snippet: '',
    body: '',
    date: '09:00',
    timestamp: 1_765_000_000_000,
    seen: true,
    flagged: false,
    hasAttachments: false,
    folder: 'INBOX',
    ...overrides,
  };
}

describe('conversation grouping', () => {
  it('strips reply and forward prefixes in both languages', () => {
    expect(stripSubjectPrefixes('Re: Fwd: Бюджет')).toBe('Бюджет');
    expect(stripSubjectPrefixes('RE[2]: Ответ: Пересл: Договор')).toBe('Договор');
    expect(stripSubjectPrefixes('Отчёт')).toBe('Отчёт');
    expect(conversationSubject('re: Re:  Бюджет ')).toBe('бюджет');
  });

  it('extracts the address out of a display name', () => {
    expect(conversationAddress('Петрова Анна <A.Petrova@Company.Local>')).toBe('a.petrova@company.local');
    expect(conversationAddress('bare@example.com')).toBe('bare@example.com');
  });

  it('keeps letters with the same subject but different senders apart', () => {
    // «Re: договор» от двух контрагентов — две переписки, а не одна.
    expect(conversationKey(mail({ id: 'a', subject: 'Договор' })))
      .not.toBe(conversationKey(mail({ id: 'b', subject: 'Re: Договор', from: 'Bob <bob@example.com>' })));
  });

  it('groups a thread and orders it from old to new', () => {
    const thread = groupIntoConversations([
      mail({ id: '2', subject: 'Re: Бюджет', timestamp: 300, seen: false }),
      mail({ id: '1', subject: 'Бюджет', timestamp: 100 }),
      mail({ id: '3', subject: 'Про другое', timestamp: 200 }),
    ]);

    expect(thread).toHaveLength(2);
    expect(thread[0].mails.map((item) => item.id)).toEqual(['1', '2']);
    expect(thread[0].latest.id).toBe('2');
    expect(thread[0].unread).toBe(1);
    expect(thread[0].subject).toBe('Бюджет');
    expect(thread[1].mails).toHaveLength(1);
  });

  it('does not mutate the input list', () => {
    const input = [
      mail({ id: '1', timestamp: 100 }),
      mail({ id: '2', subject: 'Re: Бюджет', timestamp: 50 }),
    ];
    const snapshot = input.map((item) => item.id);
    groupIntoConversations(input);
    expect(input.map((item) => item.id)).toEqual(snapshot);
  });

  it('declines the count label correctly', () => {
    expect(conversationCountLabel(1, 'ru')).toBe('');
    expect(conversationCountLabel(2, 'ru')).toBe('2 письма');
    expect(conversationCountLabel(5, 'ru')).toBe('5 писем');
    expect(conversationCountLabel(11, 'ru')).toBe('11 писем');
    expect(conversationCountLabel(21, 'ru')).toBe('21 письмо');
    expect(conversationCountLabel(3, 'en')).toBe('3 messages');
  });
});
