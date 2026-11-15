// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../i18n';
import {
  buildEmptyEventDraft,
  buildEventDraftFromMail,
  buildEventIcs,
  CALENDAR_STORAGE_KEY,
  findConflictingEvents,
  getDueReminder,
  getEventsForDate,
  isValidIsoDate,
  occursOnDate,
  reminderKey,
  useCalendarStore,
  type CalendarEventItem,
} from './calendarStore';

function event(overrides: Partial<CalendarEventItem> = {}): CalendarEventItem {
  return {
    ...buildEmptyEventDraft('2026-01-05'),
    id: 'event-1',
    title: 'Planning',
    createdAt: '2026-01-01T00:00:00.000Z',
    ...overrides,
  };
}

beforeEach(() => {
  useCalendarStore.getState().setOwner(null);
  localStorage.clear();
  useLocaleStore.setState({ locale: 'en' });
});

afterEach(() => {
  useCalendarStore.getState().setOwner(null);
  localStorage.clear();
  vi.useRealTimers();
});

describe('browser calendar storage', () => {
  it('separates accounts and demo data and restores reminders for their owner', () => {
    const store = useCalendarStore.getState();
    store.setOwner('alice@example.com');
    expect(useCalendarStore.getState().events).toEqual([]);
    const saved = store.upsertEvent({ ...buildEmptyEventDraft('2026-01-05'), title: 'Alice only' });
    const key = reminderKey(saved.id, saved.date);
    store.dismissReminder(key);

    store.setOwner('bob@example.com');
    expect(useCalendarStore.getState().events).toEqual([]);
    expect(useCalendarStore.getState().dismissedReminderKeys).toEqual([]);
    store.upsertEvent({ ...buildEmptyEventDraft('2026-01-05'), title: 'Bob only' });

    store.setOwner('alice@example.com', true);
    expect(useCalendarStore.getState().events.length).toBeGreaterThan(0);
    expect(useCalendarStore.getState().events.some((item) => item.title === 'Alice only')).toBe(false);

    store.setOwner(null);
    expect(useCalendarStore.getState().events).toEqual([]);
    store.setOwner('alice@example.com');
    expect(useCalendarStore.getState().events.map((item) => item.title)).toEqual(['Alice only']);
    expect(useCalendarStore.getState().dismissedReminderKeys).toEqual([key]);
    expect(localStorage.getItem(`${CALENDAR_STORAGE_KEY}:user:${encodeURIComponent('bob@example.com')}`)).toContain('Bob only');

    store.upsertEvent({ ...saved, title: 'Updated' });
    expect(useCalendarStore.getState().dismissedReminderKeys).toEqual([]);
    store.deleteEvent(saved.id);
    expect(useCalendarStore.getState().events).toEqual([]);
  });

  it('ignores malformed records and normalizes incomplete stored values', () => {
    const key = `${CALENDAR_STORAGE_KEY}:user:alice`;
    localStorage.setItem(key, JSON.stringify([
      null,
      { id: 'invalid-date', title: 'bad', date: '2026-02-30', startTime: '10:00', endTime: '11:00' },
      { id: 'invalid-time', title: 'bad', date: '2026-02-28', startTime: '25:00', endTime: '11:00' },
      { id: 'valid', title: 'safe', date: '2026-02-28', startTime: '10:00', endTime: '11:00', attendees: [1, 'a@example.com'] },
    ]));
    useCalendarStore.getState().setOwner('alice');
    const events = useCalendarStore.getState().events;
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ id: 'valid', priority: 'medium', recurrence: 'none', attendees: ['a@example.com'], location: '', description: '' });
    expect(isValidIsoDate('2026-02-30')).toBe(false);
    expect(isValidIsoDate('2028-02-29')).toBe(true);
  });

  it('localizes demo samples without overwriting user edits', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 0, 5, 9));
    const store = useCalendarStore.getState();
    store.setOwner('demo', true);
    const original = useCalendarStore.getState().events.find((item) => item.id === 'seed-ev-1')!;
    expect(original.title).toBe('Monthly payment register review');
    store.setDemoLocale('ru');
    const russian = useCalendarStore.getState().events.find((item) => item.id === original.id)!;
    expect(russian.title).toBe('Сверка реестра платежей за месяц');
    store.upsertEvent({ ...russian, title: 'My own title' });
    store.setDemoLocale('en');
    const edited = useCalendarStore.getState().events.find((item) => item.id === original.id)!;
    expect(edited.title).toBe('My own title');
    expect(edited.location).toBe('North meeting room · floor 4');
  });
});

describe('recurrences, conflicts and reminders', () => {
  it('generates occurrences without shifting the original series and applies filters', () => {
    const weekly = event({ recurrence: 'weekly', date: '2026-01-05' });
    const monthly = event({ id: 'monthly', recurrence: 'monthly', date: '2026-01-31' });
    const weekdays = event({ id: 'weekdays', recurrence: 'weekdays', date: '2026-01-05', priority: 'low' });
    expect(occursOnDate(weekly, '2026-01-12')).toBe(true);
    expect(occursOnDate(weekly, '2026-01-06')).toBe(false);
    expect(occursOnDate(monthly, '2026-02-28')).toBe(false);
    expect(occursOnDate(monthly, '2026-03-31')).toBe(true);
    expect(occursOnDate(weekdays, '2026-01-10')).toBe(false);
    expect(getEventsForDate([weekly], '2026-01-12')[0].date).toBe('2026-01-12');
    expect(weekly.date).toBe('2026-01-05');
    expect(getEventsForDate([weekly, weekdays], '2026-01-12', { high: true, medium: true, low: false, fromMail: true }).map((item) => item.id)).toEqual(['event-1']);
  });

  it('detects timed and all-day conflicts but ignores free events and the edited series', () => {
    const busy = event();
    const free = event({ id: 'free', busyStatus: 'free' });
    const allDay = event({ id: 'all-day', allDay: true });
    const candidate = { date: '2026-01-05', startTime: '10:30', endTime: '11:30', allDay: false };
    expect(findConflictingEvents([busy, free, allDay], candidate).map((item) => item.id)).toEqual(['all-day', 'event-1']);
    expect(findConflictingEvents([busy], { ...candidate, busyStatus: 'free' })).toEqual([]);
    expect(findConflictingEvents([busy], { ...candidate, id: busy.id })).toEqual([]);
    expect(findConflictingEvents([busy], { ...candidate, startTime: '11:00', endTime: '12:00' })).toEqual([]);
  });

  it('reminds each occurrence, honors dismiss/snooze, and never brings a reminder forward', () => {
    const weekly = event({ recurrence: 'weekly' });
    const occurrence = reminderKey(weekly.id, '2026-01-12');
    const now = new Date(2026, 0, 12, 9, 46);
    expect(getDueReminder([weekly], [reminderKey(weekly.id, '2026-01-05')], {}, now)?.date).toBe('2026-01-12');
    expect(getDueReminder([weekly], [occurrence], {}, now)).toBeNull();
    const snoozedUntil = new Date(2026, 0, 12, 10, 10).getTime();
    expect(getDueReminder([weekly], [], { [occurrence]: snoozedUntil }, now)).toBeNull();
    expect(getDueReminder([weekly], [], { [occurrence]: snoozedUntil }, new Date(2026, 0, 12, 10, 10))?.id).toBe(weekly.id);

    const earlySnooze = new Date(2026, 0, 12, 9, 10).getTime();
    expect(getDueReminder([weekly], [], { [occurrence]: earlySnooze }, new Date(2026, 0, 12, 9, 11))).toBeNull();
    const tomorrow = event({ id: 'tomorrow', date: '2026-01-13', startTime: '09:00', endTime: '10:00', reminderMinutes: 1440 });
    expect(getDueReminder([tomorrow], [], {}, new Date(2026, 0, 12, 9, 0))?.id).toBe('tomorrow');
  });
});

describe('mail drafts and calendar export', () => {
  const mail = { id: 'm1', subject: 'Re: Planning', from: 'Alice <alice@example.com>', body: '', flagged: true };

  it('extracts Russian and English dates and times, including a missing year', () => {
    const now = new Date(2026, 9, 2, 10);
    const russian = buildEventDraftFromMail({ ...mail, body: 'Встреча 7 ноября с 14:30 до 15:15' }, now);
    expect(russian).toMatchObject({ date: '2026-11-07', startTime: '14:30', endTime: '15:15', title: 'Planning', attendees: ['alice@example.com'], fromMail: true, sourceMailId: 'm1', priority: 'high' });
    const english = buildEventDraftFromMail({ ...mail, body: 'Meet November 7 at 3:30 PM - 4:45 PM' }, now);
    expect(english).toMatchObject({ date: '2026-11-07', startTime: '15:30', endTime: '16:45' });
    const nextYear = buildEventDraftFromMail({ ...mail, body: 'Meet January 4 at 10:00' }, new Date(2026, 11, 20, 10));
    expect(nextYear.date).toBe('2027-01-04');
  });

  it('exports valid all-day boundaries, escaped text, recurrence and folded UTF-8 lines', () => {
    const item = event({
      date: '2026-01-31',
      allDay: true,
      recurrence: 'monthly',
      title: 'Привет, команда; ' + 'тест😀'.repeat(20),
      description: 'One\nTwo, three; four',
      attendees: ['alice@example.com', 'bad\r\nATTENDEE:evil'],
      reminderMinutes: 15,
    });
    const ics = buildEventIcs(item);
    const unfolded = ics.replace(/\r\n /g, '');
    expect(unfolded).toContain('DTSTART;VALUE=DATE:20260131');
    expect(unfolded).toContain('DTEND;VALUE=DATE:20260201');
    expect(unfolded).toContain('RRULE:FREQ=MONTHLY');
    expect(unfolded).toContain('DESCRIPTION:One\\nTwo\\, three\\; four');
    expect(unfolded).toContain('ATTENDEE:mailto:alice@example.com');
    expect(unfolded).not.toContain('ATTENDEE:evil');
    expect(unfolded).toContain('TRIGGER:-PT15M');
    for (const line of ics.split('\r\n')) expect(new TextEncoder().encode(line).length).toBeLessThanOrEqual(75);
  });
});
