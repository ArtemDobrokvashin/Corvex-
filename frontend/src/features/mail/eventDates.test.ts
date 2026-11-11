import { describe, expect, it } from 'vitest';
import { findEventDates } from './eventDates';

describe('mail date suggestions', () => {
  it('finds explicit numeric dates and times without assuming a timezone', () => {
    const text = 'Встреча 10.11.2026 в 9:05, резерв 2026-11-11 at 14:30.';
    const dates = findEventDates(text);
    expect(dates.map(({ date, time }) => ({ date, time }))).toEqual([{ date: '2026-11-10', time: '09:05' }, { date: '2026-11-11', time: '14:30' }]);
    for (const date of dates) expect(text.slice(date.start, date.end)).toBe(date.original);
  });
  it('accepts date-only and leap day but rejects invalid dates and times', () => {
    expect(findEventDates('29.02.2024 29.02.2025 31.04.2026 2026-13-01 01.01.2026 24:00').map((v) => v.date)).toEqual(['2024-02-29']);
  });
  it('does not guess partial, ambiguous or embedded numeric dates', () => {
    expect(findEventDates('10/11/2026 завтра 10.11 1232026-11-10')).toEqual([]);
  });
});
