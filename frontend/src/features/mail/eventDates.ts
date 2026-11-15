/** Conservative suggestions only: explicit numeric dates with a four-digit year.
 * No timezone is guessed. The user must review date, time and zone before saving.
 */
export interface EventDateSuggestion {
  start: number;
  end: number;
  original: string;
  date: string;
  time: string | null;
}

export function findEventDates(text: string): EventDateSuggestion[] {
  const matches: EventDateSuggestion[] = [];
  const pattern = /(^|[^\d])(\d{4}-\d{2}-\d{2}|\d{2}\.\d{2}\.\d{4})(?:[ \t]+(?:в[ \t]+|at[ \t]+)?(\d{1,2}):(\d{2}))?(?!\d|:\d|\.\d)/gi;
  for (const match of text.matchAll(pattern)) {
    const value = match[2];
    const iso = value.includes('-');
    const parts = value.split(iso ? '-' : '.').map(Number);
    const [year, month, day] = iso ? parts : [parts[2], parts[1], parts[0]];
    if (year < 1900 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) continue;
    const date = new Date(Date.UTC(year, month - 1, day));
    if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) continue;
    const hour = match[3] === undefined ? null : Number(match[3]);
    const minute = match[4] === undefined ? null : Number(match[4]);
    if (hour !== null && (hour > 23 || minute! > 59)) continue;
    const start = match.index! + match[1].length;
    const original = match[0].slice(match[1].length);
    matches.push({ start, end: start + original.length, original,
      date: `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`,
      time: hour === null ? null : `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}` });
  }
  return matches;
}
