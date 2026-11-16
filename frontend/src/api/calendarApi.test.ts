import { describe, expect, it } from 'vitest';
import {
  instantToZoned, itemToSave, viewToItem, zonedToInstant, isServerEventId,
} from './calendarApi';
import type { CalendarEventItem } from '../store/calendarStore';

const item = (patch: Partial<CalendarEventItem> = {}): CalendarEventItem => ({
  id: 'ev-1',
  title: 'Планёрка',
  date: '2026-10-19',
  startTime: '09:00',
  endTime: '10:00',
  allDay: false,
  priority: 'medium',
  fromMail: false,
  reminderMinutes: 15,
  recurrence: 'none',
  busyStatus: 'busy',
  location: '',
  attendees: [],
  description: '',
  createdAt: '2026-10-01T00:00:00.000Z',
  ...patch,
});

describe('календарь: конвертация времени', () => {
  it('переводит местное время в UTC с учётом летнего времени зоны события', () => {
    // Amsterdam летом UTC+2, зимой UTC+1: одно и то же «09:00» даёт разные моменты.
    expect(zonedToInstant('2026-07-01', '09:00', 'Europe/Amsterdam')).toBe('2026-07-01T07:00:00.000Z');
    expect(zonedToInstant('2026-12-01', '09:00', 'Europe/Amsterdam')).toBe('2026-12-01T08:00:00.000Z');
  });

  it('сохраняет 09:00 после перехода на летнее время', () => {
    const summer = instantToZoned(zonedToInstant('2026-07-01', '09:00', 'Europe/Amsterdam'), 'Europe/Amsterdam');
    const winter = instantToZoned(zonedToInstant('2026-12-01', '09:00', 'Europe/Amsterdam'), 'Europe/Amsterdam');
    expect(summer).toEqual({ date: '2026-07-01', time: '09:00' });
    expect(winter).toEqual({ date: '2026-12-01', time: '09:00' });
  });

  it('показывает событие в его собственной зоне, а не в зоне браузера', () => {
    const view = {
      id: '8f3a3f14-9a1c-4f3c-9a4e-0d7c1f0a6b21',
      revision: 3,
      title: 'Созвон',
      description: '',
      location: 'Zoom',
      startsAt: '2026-10-19T16:00:00.000Z',
      endsAt: '2026-10-19T17:00:00.000Z',
      allDay: false,
      timeZone: 'Asia/Vladivostok',
      priority: 'high' as const,
      busyStatus: 'busy' as const,
      recurrence: 'none' as const,
      reminderMinutes: 30,
      attendees: ['i.ivanov@company.local'],
      source: { kind: 'NONE' as const, mailId: null, subject: null, sender: null },
      createdAt: '2026-10-01T00:00:00.000Z',
      updatedAt: '2026-10-02T00:00:00.000Z',
    };
    const mapped = viewToItem(view);
    expect(mapped.date).toBe('2026-10-20');
    expect(mapped.startTime).toBe('02:00');
    expect(mapped.revision).toBe(3);
  });

  it('отправляет ревизию только для серверного события', () => {
    expect(itemToSave(item()).revision).toBeUndefined();
    const saved = itemToSave(item({ id: '8f3a3f14-9a1c-4f3c-9a4e-0d7c1f0a6b21', revision: 4 }), 'UTC');
    expect(saved.revision).toBe(4);
    expect(saved.startsAt).toBe('2026-10-19T09:00:00.000Z');
  });

  it('помечает источником письмо только при fromMail', () => {
    expect(itemToSave(item()).source.kind).toBe('NONE');
    const fromMail = itemToSave(item({ fromMail: true, sourceMailId: 'mail-1', sourceMailSubject: 'Договор', sourceMailSender: 'a@b.c' }), 'UTC');
    expect(fromMail.source).toEqual({ kind: 'MAIL', mailId: 'mail-1', subject: 'Договор', sender: 'a@b.c' });
  });

  it('различает серверный UUID и локальный id', () => {
    expect(isServerEventId('8f3a3f14-9a1c-4f3c-9a4e-0d7c1f0a6b21')).toBe(true);
    expect(isServerEventId('ev-1750000000000-abc')).toBe(false);
  });
});
