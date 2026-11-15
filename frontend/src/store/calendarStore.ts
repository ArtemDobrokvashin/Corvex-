import { create } from 'zustand';
import { cleanPersonName, emailFromHeader } from '../components/ui';
import { getCurrentLocale, translate, type Locale } from '../i18n';
import { demoMails } from '../api/demoData';

export const CALENDAR_STORAGE_KEY = 'corvex.calendar.events';

export type CalendarViewMode = 'month' | 'week' | 'day' | 'agenda';
export type EventPriority = 'high' | 'medium' | 'low';
export type EventRecurrence = 'none' | 'daily' | 'weekdays' | 'weekly' | 'monthly';
export type EventBusyStatus = 'busy' | 'tentative' | 'free';

export interface CalendarEventItem {
  id: string;
  title: string;
  date: string; // YYYY-MM-DD
  startTime: string; // HH:MM
  endTime: string; // HH:MM
  allDay: boolean;
  priority: EventPriority;
  fromMail: boolean;
  sourceMailId?: string;
  sourceMailSubject?: string;
  sourceMailSender?: string;
  reminderMinutes: number | null; // null | 0 | 5 | 15 | 30 | 60 | 1440
  snoozedUntil?: number | null;
  recurrence: EventRecurrence;
  busyStatus: EventBusyStatus;
  location: string;
  attendees: string[];
  description: string;
  createdAt: string;
}

export type CalendarEvent = CalendarEventItem;

export interface MailEventSource {
  id: string;
  subject: string;
  from: string;
  body: string;
  snippet?: string;
  flagged?: boolean;
}

export type CalendarEventDraft = Omit<CalendarEventItem, 'id' | 'createdAt'> & {
  id?: string;
};

export interface CalendarLayerFilters {
  high: boolean;
  medium: boolean;
  low: boolean;
  fromMail: boolean;
}

export function formatIsoDate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export function parseIsoDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y || 2026, (m || 1) - 1, d || 1);
}

export function addDaysIso(iso: string, delta: number): string {
  const d = parseIsoDate(iso);
  d.setDate(d.getDate() + delta);
  return formatIsoDate(d);
}

function padHm(hours: number, minutes: number): string {
  const h = ((hours % 24) + 24) % 24;
  const m = ((minutes % 60) + 60) % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

function addMinutesHm(hm: string, addMinutes: number): string {
  const [h, m] = hm.split(':').map(Number);
  const total = Math.min(23 * 60 + 59, (Number.isFinite(h) ? h : 10) * 60 + (Number.isFinite(m) ? m : 0) + addMinutes);
  return padHm(Math.floor(total / 60), total % 60);
}

function buildSeedEvents(now = new Date(), locale: Locale = getCurrentLocale()): CalendarEventItem[] {
  const today = formatIsoDate(now);
  const tomorrow = addDaysIso(today, 1);
  const inTwoDays = addDaysIso(today, 2);
  const yesterday = addDaysIso(today, -1);

  const seeds: CalendarEventItem[] = [
    {
      id: 'seed-ev-1',
      title: 'Сверка реестра платежей за месяц',
      date: today,
      startTime: '11:00',
      endTime: '11:45',
      allDay: false,
      priority: 'high',
      fromMail: true,
      sourceMailId: 'm1',
      sourceMailSubject: 'Сводка по платежам за октябрь',
      sourceMailSender: 'Петрова Анна <a.petrova@company.local>',
      reminderMinutes: 15,
      recurrence: 'none',
      busyStatus: 'busy',
      location: 'Переговорная «Север» · 4 этаж',
      attendees: ['a.petrova@company.local'],
      description: 'Подтвердить 3 платежа свыше лимита по выгрузке из 1С.',
      createdAt: now.toISOString(),
    },
    {
      id: 'seed-ev-2',
      title: 'Планирование спринта и синхронизация',
      date: today,
      startTime: '15:00',
      endTime: '16:00',
      allDay: false,
      priority: 'medium',
      fromMail: false,
      reminderMinutes: 15,
      recurrence: 'weekly',
      busyStatus: 'busy',
      location: 'Видеовстреча Corvex Meet',
      attendees: ['d.kozlov@company.local', 'it@company.local'],
      description: 'Статус миграции почтовых ящиков, задачи инфраструктуры и безопасность.',
      createdAt: now.toISOString(),
    },
    {
      id: 'seed-ev-3',
      title: 'Код-ревью и проверка квот хранилища',
      date: tomorrow,
      startTime: '10:30',
      endTime: '11:15',
      allDay: false,
      priority: 'low',
      fromMail: false,
      reminderMinutes: 30,
      recurrence: 'weekdays',
      busyStatus: 'tentative',
      location: 'Кабинет 305',
      attendees: ['d.kozlov@company.local'],
      description: 'Ежедневная короткая синхронизация инженерной команды.',
      createdAt: now.toISOString(),
    },
    {
      id: 'seed-ev-4',
      title: 'Согласование графика отпусков подразделений',
      date: inTwoDays,
      startTime: '14:00',
      endTime: '15:00',
      allDay: false,
      priority: 'medium',
      fromMail: true,
      sourceMailId: 'm4',
      sourceMailSubject: 'График отпусков на 2027 год — заполните до 1 ноября',
      sourceMailSender: 'HR <hr@company.local>',
      reminderMinutes: 60,
      recurrence: 'none',
      busyStatus: 'busy',
      location: 'HR-портал / Переговорная «Юг»',
      attendees: ['hr@company.local'],
      description: 'Финальное утверждение графиков руководителями подразделений.',
      createdAt: now.toISOString(),
    },
    {
      id: 'seed-ev-5',
      title: 'Итоги пилотной миграции 50 ящиков',
      date: yesterday,
      startTime: '16:00',
      endTime: '16:45',
      allDay: false,
      priority: 'low',
      fromMail: true,
      sourceMailId: 'm5',
      sourceMailSubject: 'Миграция со старого почтового сервера: пилот — 50 ящиков',
      sourceMailSender: 'Козлов Дмитрий <d.kozlov@company.local>',
      reminderMinutes: null,
      recurrence: 'none',
      busyStatus: 'free',
      location: 'Онлайн',
      attendees: ['d.kozlov@company.local'],
      description: 'Разбор замечаний пилотной группы перед переводом бухгалтерии.',
      createdAt: now.toISOString(),
    },
  ];
  return seeds.map((event) => {
    const mail = event.sourceMailId ? demoMails(locale).find((item) => item.id === event.sourceMailId) : undefined;
    return {
      ...event,
      title: translate(locale, event.title),
      location: translate(locale, event.location),
      description: translate(locale, event.description),
      sourceMailSubject: mail?.subject ?? event.sourceMailSubject,
      sourceMailSender: mail?.from ?? event.sourceMailSender,
    };
  });
}

export function isValidIsoDate(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(year, month - 1, day);
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day;
}

function isTime(value: unknown): value is string {
  return typeof value === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(value);
}

function loadStoredEvents(key: string, demoMode: boolean): CalendarEventItem[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) {
      const initial = demoMode ? buildSeedEvents() : [];
      window.localStorage.setItem(key, JSON.stringify(initial));
      return initial;
    }
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    const unique = new Map<string, CalendarEventItem>();
    for (const item of parsed) {
      if (!item || typeof item !== 'object') continue;
      const event = item as Partial<CalendarEventItem>;
      if (typeof event.id !== 'string' || typeof event.title !== 'string' || !isValidIsoDate(event.date) || !isTime(event.startTime) || !isTime(event.endTime)) continue;
      unique.set(event.id, {
        id: event.id,
        title: event.title,
        date: event.date,
        startTime: event.startTime,
        endTime: event.endTime,
        allDay: event.allDay === true,
        priority: event.priority === 'high' || event.priority === 'low' ? event.priority : 'medium',
        fromMail: event.fromMail === true,
        sourceMailId: typeof event.sourceMailId === 'string' ? event.sourceMailId : undefined,
        sourceMailSubject: typeof event.sourceMailSubject === 'string' ? event.sourceMailSubject : undefined,
        sourceMailSender: typeof event.sourceMailSender === 'string' ? event.sourceMailSender : undefined,
        reminderMinutes: typeof event.reminderMinutes === 'number' && [0, 5, 15, 30, 60, 1440].includes(event.reminderMinutes) ? event.reminderMinutes : null,
        recurrence: event.recurrence === 'daily' || event.recurrence === 'weekdays' || event.recurrence === 'weekly' || event.recurrence === 'monthly' ? event.recurrence : 'none',
        busyStatus: event.busyStatus === 'free' || event.busyStatus === 'tentative' ? event.busyStatus : 'busy',
        location: typeof event.location === 'string' ? event.location : '',
        attendees: Array.isArray(event.attendees) ? event.attendees.filter((value): value is string => typeof value === 'string') : [],
        description: typeof event.description === 'string' ? event.description : '',
        createdAt: typeof event.createdAt === 'string' ? event.createdAt : new Date().toISOString(),
      });
    }
    return [...unique.values()];
  } catch {
    return [];
  }
}

function persistValue(key: string | null, value: unknown) {
  if (!key || typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Storage may be disabled or full. The current tab still keeps its state.
  }
}

/** Stable key for one occurrence of a recurring event. */
export function reminderKey(id: string, date: string): string {
  return `${id}:${date}`;
}

export function buildEmptyEventDraft(date: string, startTime = '10:00'): CalendarEventDraft {
  const safeStartTime = isTime(startTime) ? startTime : '10:00';
  const [hour, minute] = safeStartTime.split(':').map(Number);
  const total = Math.min(23 * 60 + 59, hour * 60 + minute + 60);
  return {
    title: '', date, startTime: safeStartTime, endTime: padHm(Math.floor(total / 60), total % 60),
    allDay: false, priority: 'medium', fromMail: false, reminderMinutes: 15,
    recurrence: 'none', busyStatus: 'busy', location: '', attendees: [], description: '',
  };
}

/** Return the first due occurrence, including tomorrow's 24-hour reminders. */
export function getDueReminder(
  events: CalendarEventItem[],
  dismissed: string[],
  snoozed: Record<string, number>,
  now: Date,
): CalendarEventItem | null {
  const today = formatIsoDate(now);
  for (const date of [today, addDaysIso(today, 1)]) {
    for (const event of getEventsForDate(events, date)) {
      if (event.reminderMinutes === null) continue;
      const key = reminderKey(event.id, date);
      if (dismissed.includes(key)) continue;
      const start = parseIsoDate(date);
      const [hours, minutes] = event.allDay ? [9, 0] : event.startTime.split(':').map(Number);
      start.setHours(hours, minutes, 0, 0);
      const end = parseIsoDate(date);
      const [endHours, endMinutes] = event.allDay ? [23, 59] : event.endTime.split(':').map(Number);
      end.setHours(endHours, endMinutes, 0, 0);
      const scheduledTrigger = start.getTime() - event.reminderMinutes * 60_000;
      // Snoozing must never pull a future reminder forward.
      const trigger = Math.max(scheduledTrigger, snoozed[key] ?? 0);
      // A snoozed reminder remains actionable even if the event has just ended.
      const expires = Math.max(end.getTime(), (snoozed[key] ?? 0) + 60_000);
      if (now.getTime() >= trigger && now.getTime() <= expires) return event;
    }
  }
  return null;
}

export function occursOnDate(event: CalendarEventItem, targetIso: string): boolean {
  if (event.date === targetIso) return true;
  if (event.recurrence === 'none' || targetIso < event.date) return false;

  const start = parseIsoDate(event.date);
  const target = parseIsoDate(targetIso);

  if (event.recurrence === 'daily') return true;
  if (event.recurrence === 'weekdays') {
    const day = target.getDay();
    return day >= 1 && day <= 5;
  }
  if (event.recurrence === 'weekly') {
    return start.getDay() === target.getDay();
  }
  if (event.recurrence === 'monthly') {
    return start.getDate() === target.getDate();
  }
  return false;
}

export function matchesLayerFilters(event: CalendarEventItem, filters: CalendarLayerFilters): boolean {
  const priorityAllowed =
    (event.priority === 'high' && filters.high)
    || (event.priority === 'medium' && filters.medium)
    || (event.priority === 'low' && filters.low);
  if (!priorityAllowed) return false;
  if (event.fromMail && !filters.fromMail) return false;
  return true;
}

export function getEventsForDate(
  events: CalendarEventItem[],
  targetIso: string,
  filters?: CalendarLayerFilters,
): CalendarEventItem[] {
  return events
    .filter((ev) => (!filters || matchesLayerFilters(ev, filters)) && occursOnDate(ev, targetIso))
    .map((ev) => (ev.date === targetIso ? ev : { ...ev, date: targetIso }))
    .sort((a, b) => {
      if (a.allDay !== b.allDay) return a.allDay ? -1 : 1;
      return a.startTime.localeCompare(b.startTime);
    });
}

export function findConflictingEvents(
  events: CalendarEventItem[],
  candidate: { id?: string; date: string; startTime: string; endTime: string; allDay: boolean; busyStatus?: EventBusyStatus },
): CalendarEventItem[] {
  if (candidate.busyStatus === 'free') return [];
  const dayEvents = getEventsForDate(events, candidate.date);
  return dayEvents.filter((ev) => {
    if (ev.id === candidate.id || ev.busyStatus === 'free') return false;
    return candidate.allDay || ev.allDay || (candidate.startTime < ev.endTime && candidate.endTime > ev.startTime);
  });
}

const WEEKDAY_STEMS: Array<{ pattern: RegExp; jsDay: number }> = [
  { pattern: /понедельн|monday/i, jsDay: 1 },
  { pattern: /вторник|tuesday/i, jsDay: 2 },
  { pattern: /сред[ауе]|wednesday/i, jsDay: 3 },
  { pattern: /четверг|thursday/i, jsDay: 4 },
  { pattern: /пятниц|friday/i, jsDay: 5 },
  { pattern: /суббот|saturday/i, jsDay: 6 },
  { pattern: /воскресен|sunday/i, jsDay: 0 },
];

/**
 * Извлекает предполагаемую дату и интервал времени из темы/текста письма,
 * чтобы при создании события из письма форма сразу предлагала осмысленный слот.
 */
export function buildEventDraftFromMail(
  mail: MailEventSource,
  now = new Date(),
): CalendarEventDraft {
  const combined = `${mail.subject}\n${mail.body || mail.snippet || ''}`;
  let targetDate = new Date(now.getFullYear(), now.getMonth(), now.getDate());

  const isoMatch = combined.match(/\b(20\d{2})-(\d{2})-(\d{2})\b/);
  const numericMatch = combined.match(/\b(\d{1,2})\.(\d{1,2})(?:\.(20\d{2}))?\b/);
  const russianMonth = combined.match(/(?:^|[^\d])(\d{1,2})\s+(январ[ьяе]|феврал[ьяе]|март[ае]?|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|август[ае]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе])(?:\s+(20\d{2}))?/i);
  const englishMonth = combined.match(/\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(20\d{2}))?\b/i);
  const monthStems = [/^январ|^jan/i, /^феврал|^feb/i, /^март|^mar/i, /^апрел|^apr/i, /^ма[йяе]|^may/i, /^июн|^jun/i, /^июл|^jul/i, /^август|^aug/i, /^сентябр|^sep/i, /^октябр|^oct/i, /^ноябр|^nov/i, /^декабр|^dec/i];

  const safeDate = (year: number, month: number, day: number): Date | null => {
    const candidate = new Date(year, month, day);
    return candidate.getFullYear() === year && candidate.getMonth() === month && candidate.getDate() === day ? candidate : null;
  };
  let explicitDate: Date | null = null;
  let explicitYear = false;
  if (isoMatch) {
    explicitDate = safeDate(Number(isoMatch[1]), Number(isoMatch[2]) - 1, Number(isoMatch[3]));
    explicitYear = true;
  } else if (numericMatch) {
    explicitYear = Boolean(numericMatch[3]);
    explicitDate = safeDate(numericMatch[3] ? Number(numericMatch[3]) : now.getFullYear(), Number(numericMatch[2]) - 1, Number(numericMatch[1]));
  } else if (russianMonth) {
    explicitYear = Boolean(russianMonth[3]);
    explicitDate = safeDate(russianMonth[3] ? Number(russianMonth[3]) : now.getFullYear(), monthStems.findIndex((pattern) => pattern.test(russianMonth[2])), Number(russianMonth[1]));
  } else if (englishMonth) {
    explicitYear = Boolean(englishMonth[3]);
    explicitDate = safeDate(englishMonth[3] ? Number(englishMonth[3]) : now.getFullYear(), monthStems.findIndex((pattern) => pattern.test(englishMonth[1])), Number(englishMonth[2]));
  }

  if (explicitDate) {
    if (!explicitYear && explicitDate < targetDate) {
      explicitDate = safeDate(explicitDate.getFullYear() + 1, explicitDate.getMonth(), explicitDate.getDate()) ?? explicitDate;
    }
    targetDate = explicitDate;
  } else if (/послезавтра|day after tomorrow/i.test(combined)) {
    targetDate.setDate(targetDate.getDate() + 2);
  } else if (/завтра|tomorrow/i.test(combined)) {
    targetDate.setDate(targetDate.getDate() + 1);
  } else {
    const weekday = WEEKDAY_STEMS.find((w) => w.pattern.test(combined));
    if (weekday) {
      const currentDay = targetDate.getDay();
      const diff = (weekday.jsDay - currentDay + 7) % 7 || 7;
      targetDate.setDate(targetDate.getDate() + diff);
    } else if (now.getHours() >= 17) {
      targetDate.setDate(targetDate.getDate() + 1);
    }
  }

  let startTime = '11:00';
  let endTime = '12:00';

  const rangeMatch = combined.match(/\b([01]?\d|2[0-3]):([0-5]\d)\s*[–—-]\s*([01]?\d|2[0-3]):([0-5]\d)\b/)
    ?? combined.match(/(?:с|from)\s+([01]?\d|2[0-3]):([0-5]\d)\s+(?:до|to)\s+([01]?\d|2[0-3]):([0-5]\d)/i);
  const singleTimeMatch = combined.match(/(?:в|к|at)\s+([01]?\d|2[0-3]):([0-5]\d)\b/i)
    ?? combined.match(/\b([01]\d|2[0-3]):([0-5]\d)\b/);

  const amPmRange = combined.match(/\b(1[0-2]|0?[1-9])(?::([0-5]\d))?\s*(am|pm)\s*[–—-]\s*(1[0-2]|0?[1-9])(?::([0-5]\d))?\s*(am|pm)\b/i);
  const amPmTime = combined.match(/\b(1[0-2]|0?[1-9])(?::([0-5]\d))?\s*(am|pm)\b/i);
  const meridiemHour = (hour: string, period: string) => Number(hour) % 12 + (period.toLowerCase() === 'pm' ? 12 : 0);
  if (amPmRange) {
    startTime = padHm(meridiemHour(amPmRange[1], amPmRange[3]), Number(amPmRange[2] ?? 0));
    endTime = padHm(meridiemHour(amPmRange[4], amPmRange[6]), Number(amPmRange[5] ?? 0));
  } else if (amPmTime && !rangeMatch) {
    startTime = padHm(meridiemHour(amPmTime[1], amPmTime[3]), Number(amPmTime[2] ?? 0));
    endTime = addMinutesHm(startTime, 45);
  } else if (rangeMatch) {
    startTime = padHm(Number(rangeMatch[1]), Number(rangeMatch[2]));
    endTime = padHm(Number(rangeMatch[3]), Number(rangeMatch[4]));
  } else if (singleTimeMatch) {
    startTime = padHm(Number(singleTimeMatch[1]), Number(singleTimeMatch[2]));
    endTime = addMinutesHm(startTime, 45);
  } else {
    const nextHour = now.getHours() >= 8 && now.getHours() < 17 ? now.getHours() + 1 : 10;
    startTime = padHm(nextHour, 0);
    endTime = padHm(nextHour + 1, 0);
  }

  const cleanSubject = mail.subject.replace(/^(?:re|fwd|fw|отв|пер):\s*/gi, '').trim() || mail.subject;
  const senderEmail = emailFromHeader(mail.from);
  const senderLabel = senderEmail || cleanPersonName(mail.from);
  const excerpt = (mail.body || mail.snippet || '').trim().slice(0, 600);

  return {
    title: cleanSubject,
    date: formatIsoDate(targetDate),
    startTime,
    endTime,
    allDay: false,
    priority: mail.flagged ? 'high' : 'medium',
    fromMail: true,
    sourceMailId: mail.id,
    sourceMailSubject: mail.subject,
    sourceMailSender: mail.from,
    reminderMinutes: 15,
    recurrence: 'none',
    busyStatus: 'busy',
    location: '',
    attendees: senderLabel ? [senderLabel] : [],
    description: excerpt
      ? translate(getCurrentLocale(), 'Основание — письмо от {name}:\n«{excerpt}»', { name: cleanPersonName(mail.from), excerpt })
      : '',
  };
}

function escapeIcsText(value: string): string {
  return value
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,')
    .replace(/\r?\n/g, '\\n');
}

function toIcsDateTime(isoDate: string, hm: string): string {
  const [y, m, d] = isoDate.split('-');
  const [hh, mm] = hm.split(':');
  return `${y}${m}${d}T${hh}${mm}00`;
}

/** RFC 5545 lines are at most 75 UTF-8 octets; never split a Unicode character. */
function foldIcsLine(line: string): string {
  let result = '';
  let bytes = 0;
  for (const character of line) {
    const codePoint = character.codePointAt(0) ?? 0;
    const size = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
    if (bytes + size > 75) {
      result += '\r\n ';
      bytes = 1;
    }
    result += character;
    bytes += size;
  }
  return result;
}

export function buildEventIcs(event: CalendarEventItem): string {
  const uid = `${encodeURIComponent(event.id)}@corvex.local`;
  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  const dtStart = event.allDay
    ? `DTSTART;VALUE=DATE:${event.date.replace(/-/g, '')}`
    : `DTSTART:${toIcsDateTime(event.date, event.startTime)}`;
  const dtEnd = event.allDay
    ? `DTEND;VALUE=DATE:${addDaysIso(event.date, 1).replace(/-/g, '')}`
    : `DTEND:${toIcsDateTime(event.date, event.endTime)}`;
  const priorityNum = event.priority === 'high' ? 1 : event.priority === 'medium' ? 5 : 9;

  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Corvex//Corporate Calendar//RU',
    'CALSCALE:GREGORIAN',
    'BEGIN:VEVENT',
    `UID:${uid}`,
    `DTSTAMP:${stamp}`,
    dtStart,
    dtEnd,
    `SUMMARY:${escapeIcsText(event.title)}`,
    `PRIORITY:${priorityNum}`,
  ];

  if (event.location) lines.push(`LOCATION:${escapeIcsText(event.location)}`);
  if (event.description) lines.push(`DESCRIPTION:${escapeIcsText(event.description)}`);
  if (event.recurrence !== 'none') {
    const rrule =
      event.recurrence === 'daily'
        ? 'FREQ=DAILY'
        : event.recurrence === 'weekdays'
          ? 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR'
          : event.recurrence === 'weekly'
            ? 'FREQ=WEEKLY'
            : 'FREQ=MONTHLY';
    lines.push(`RRULE:${rrule}`);
  }
  for (const attendee of event.attendees) {
    if (/^[^\s@,;:]+@[^\s@,;:]+$/.test(attendee)) {
      lines.push(`ATTENDEE:mailto:${attendee}`);
    }
  }
  if (event.reminderMinutes !== null) {
    lines.push(
      'BEGIN:VALARM',
      `TRIGGER:${event.reminderMinutes === 0 ? '' : '-'}PT${Math.max(0, event.reminderMinutes)}M`,
      'ACTION:DISPLAY',
      `DESCRIPTION:${escapeIcsText(event.title)}`,
      'END:VALARM',
    );
  }
  lines.push('END:VEVENT', 'END:VCALENDAR');
  return `${lines.map(foldIcsLine).join('\r\n')}\r\n`;
}

export function downloadEventAsIcs(event: CalendarEventItem) {
  if (typeof document === 'undefined') return;
  const content = buildEventIcs(event);
  const blob = new Blob([content], { type: 'text/calendar;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  const safeName = event.title.replace(/[^A-Za-zА-Яа-я0-9_-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 48) || 'corvex-event';
  link.href = url;
  link.download = `${safeName}.ics`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

interface CalendarState {
  ownerKey: string | null;
  events: CalendarEventItem[];
  widgetOpen: boolean;
  selectedDate: string;
  viewMode: CalendarViewMode;
  layers: CalendarLayerFilters;
  dismissedReminderKeys: string[];
  snoozedUntilMs: Record<string, number>;
  setOwner: (userId: string | null, demoMode?: boolean) => void;
  setDemoLocale: (locale: Locale) => void;
  setWidgetOpen: (open: boolean) => void;
  toggleWidget: () => void;
  setSelectedDate: (isoDate: string) => void;
  setViewMode: (mode: CalendarViewMode) => void;
  toggleLayer: (key: keyof CalendarLayerFilters) => void;
  upsertEvent: (draft: CalendarEventDraft) => CalendarEventItem;
  deleteEvent: (id: string) => void;
  dismissReminder: (key: string) => void;
  snoozeEventReminder: (id: string, minutes?: number, date?: string) => void;
}

export const useCalendarStore = create<CalendarState>((set, get) => ({
  ownerKey: null,
  events: [],
  widgetOpen: false,
  selectedDate: formatIsoDate(new Date()),
  viewMode: 'month',
  layers: { high: true, medium: true, low: true, fromMail: true },
  dismissedReminderKeys: [],
  snoozedUntilMs: {},
  setOwner: (userId, demoMode = false) => {
    const ownerKey = userId ? `${CALENDAR_STORAGE_KEY}:${demoMode ? 'demo' : 'user'}:${encodeURIComponent(userId)}` : null;
    if (get().ownerKey === ownerKey) return;
    let dismissedReminderKeys: string[] = [];
    let snoozedUntilMs: Record<string, number> = {};
    if (ownerKey && typeof window !== 'undefined') {
      try {
        const raw = window.localStorage.getItem(`${ownerKey}.reminders`);
        const reminders = raw ? JSON.parse(raw) as { dismissed?: unknown; snoozed?: unknown } : null;
        if (Array.isArray(reminders?.dismissed)) dismissedReminderKeys = reminders.dismissed.filter((item): item is string => typeof item === 'string');
        if (reminders?.snoozed && typeof reminders.snoozed === 'object' && !Array.isArray(reminders.snoozed)) {
          snoozedUntilMs = Object.fromEntries(Object.entries(reminders.snoozed).filter((entry): entry is [string, number] => typeof entry[1] === 'number' && Number.isFinite(entry[1])));
        }
      } catch { /* start with an empty reminder state */ }
    }
    set({
      ownerKey,
      events: ownerKey ? loadStoredEvents(ownerKey, demoMode) : [],
      dismissedReminderKeys,
      snoozedUntilMs,
      selectedDate: formatIsoDate(new Date()),
      widgetOpen: false,
    });
  },
  setDemoLocale: (locale) => set((state) => {
    if (!state.ownerKey?.includes(':demo:')) return state;
    const russian = buildSeedEvents(new Date(), 'ru');
    const english = buildSeedEvents(new Date(), 'en');
    const desired = locale === 'en' ? english : russian;
    const fields = ['title', 'location', 'description', 'sourceMailSubject', 'sourceMailSender'] as const;
    let changed = false;
    const events = state.events.map((event) => {
      const ru = russian.find((item) => item.id === event.id);
      const en = english.find((item) => item.id === event.id);
      const target = desired.find((item) => item.id === event.id);
      if (!ru || !en || !target) return event;
      const result = { ...event };
      for (const field of fields) {
        if ((event[field] === ru[field] || event[field] === en[field]) && event[field] !== target[field]) {
          Object.assign(result, { [field]: target[field] });
          changed = true;
        }
      }
      return result;
    });
    if (!changed) return state;
    persistValue(state.ownerKey, events);
    return { events };
  }),
  setWidgetOpen: (open) => set({ widgetOpen: open }),
  toggleWidget: () => set((s) => ({ widgetOpen: !s.widgetOpen })),
  setSelectedDate: (selectedDate) => set({ selectedDate }),
  setViewMode: (viewMode) => set({ viewMode }),
  toggleLayer: (key) => set((s) => ({ layers: { ...s.layers, [key]: !s.layers[key] } })),
  upsertEvent: (draft) => {
    const previous = get().events.find((event) => event.id === draft.id);
    const item: CalendarEventItem = {
      ...draft,
      id: draft.id ?? `ev-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
      title: draft.title.trim(),
      location: draft.location.trim(),
      description: draft.description.trim(),
      attendees: [...draft.attendees],
      createdAt: previous?.createdAt ?? new Date().toISOString(),
    };
    set((s) => {
      const next = previous ? s.events.map((event) => event.id === item.id ? item : event) : [...s.events, item];
      const dismissedReminderKeys = s.dismissedReminderKeys.filter((key) => !key.startsWith(`${item.id}:`));
      const snoozedUntilMs = Object.fromEntries(Object.entries(s.snoozedUntilMs).filter(([key]) => !key.startsWith(`${item.id}:`)));
      persistValue(s.ownerKey, next);
      persistValue(s.ownerKey && `${s.ownerKey}.reminders`, { dismissed: dismissedReminderKeys, snoozed: snoozedUntilMs });
      return { events: next, selectedDate: item.date, dismissedReminderKeys, snoozedUntilMs };
    });
    return item;
  },
  deleteEvent: (id) => set((s) => {
    const events = s.events.filter((event) => event.id !== id);
    const dismissedReminderKeys = s.dismissedReminderKeys.filter((key) => !key.startsWith(`${id}:`));
    const snoozedUntilMs = Object.fromEntries(Object.entries(s.snoozedUntilMs).filter(([key]) => !key.startsWith(`${id}:`)));
    persistValue(s.ownerKey, events);
    persistValue(s.ownerKey && `${s.ownerKey}.reminders`, { dismissed: dismissedReminderKeys, snoozed: snoozedUntilMs });
    return { events, dismissedReminderKeys, snoozedUntilMs };
  }),
  dismissReminder: (key) => set((s) => {
    const dismissedReminderKeys = s.dismissedReminderKeys.includes(key) ? s.dismissedReminderKeys : [...s.dismissedReminderKeys, key];
    const snoozedUntilMs = { ...s.snoozedUntilMs };
    delete snoozedUntilMs[key];
    persistValue(s.ownerKey && `${s.ownerKey}.reminders`, { dismissed: dismissedReminderKeys, snoozed: snoozedUntilMs });
    return { dismissedReminderKeys, snoozedUntilMs };
  }),
  snoozeEventReminder: (id, minutes = 10, date = formatIsoDate(new Date())) => set((s) => {
    const key = reminderKey(id, date);
    const snoozedUntilMs = { ...s.snoozedUntilMs, [key]: Date.now() + minutes * 60_000 };
    const dismissedReminderKeys = s.dismissedReminderKeys.filter((value) => value !== key);
    persistValue(s.ownerKey && `${s.ownerKey}.reminders`, { dismissed: dismissedReminderKeys, snoozed: snoozedUntilMs });
    return { dismissedReminderKeys, snoozedUntilMs };
  }),
}));
