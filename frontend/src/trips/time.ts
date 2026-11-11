import { locale, t } from '../i18n';

/**
 * Points in time are exchanged with the backend as ISO 8601 with offset. The frontend uses the time zone of the
 * system: entered values get its offset, shown values are converted into it.
 */

const pad = (value: number) => String(value).padStart(2, '0');

/** ISO 8601 with the offset of the system time zone at that moment, e.g. 2027-07-12T00:00:00+02:00. */
export function toOffsetIso(date: Date): string {
  const offsetMinutes = -date.getTimezoneOffset();
  const sign = offsetMinutes >= 0 ? '+' : '-';
  const abs = Math.abs(offsetMinutes);
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}` +
    `${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`
  );
}

/** Local day from an input of type date (yyyy-mm-dd), null if empty or invalid. */
function parseDay(day: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day);
  if (!match) {
    return null;
  }
  return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
}

/** Begin of the day (00:00) in the system time zone. */
export function startOfDayIso(day: string): string | null {
  const date = parseDay(day);
  return date ? toOffsetIso(date) : null;
}

/** End of the day (23:59:59) in the system time zone, the trip end is inclusive. */
export function endOfDayIso(day: string): string | null {
  const date = parseDay(day);
  if (!date) {
    return null;
  }
  date.setHours(23, 59, 59);
  return toOffsetIso(date);
}

/** Value of an input of type datetime-local (yyyy-mm-ddThh:mm) as ISO with offset, null if empty. */
export function localDateTimeIso(value: string): string | null {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : toOffsetIso(date);
}

/** ISO point in time as value of an input of type datetime-local (yyyy-mm-ddThh:mm) in the system time zone. */
export function toDateTimeInput(iso: string | null): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Day of an ISO point in time in the system time zone, as yyyy-mm-dd for inputs of type date. */
export function toDayInput(iso: string): string {
  const date = new Date(iso);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

// formats in the current language (Docs/i18n.md), created per call because the language can change
const format = (options: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(locale(), options);

/** Ranges of Intl use thin spaces around the dash, the app writes normal ones. */
export const plainSpaces = (text: string) => text.replace(/[\u2009\u202f]/g, ' ');

/** "12.–26. Juli 2027", "30. Juli – 2. August 2027" or "30. Dezember 2027 – 2. Januar 2028" (German). */
export function formatPeriod(startIso: string, endIso: string): string {
  return plainSpaces(format({ day: 'numeric', month: 'long', year: 'numeric' }).formatRange(new Date(startIso), new Date(endIso)));
}

/** "9.–11. Nov." or "30. Nov. – 2. Dez.": days of a stop without times and year, in the system time zone. */
export function formatDays(startIso: string, endIso: string): string {
  return plainSpaces(format({ day: 'numeric', month: 'short' }).formatRange(new Date(startIso), new Date(endIso)));
}

/** "14. Juli" or "14 Jul": one day without time and year. */
export function formatShortDay(iso: string): string {
  return format({ day: 'numeric', month: 'short' }).format(new Date(iso));
}

/** Days of a stop: a range if both are known, otherwise "ab 9. Nov." or "bis 11. Nov."; null without times. */
export function stopDays(arrivalIso: string | null | undefined, departureIso: string | null | undefined): string | null {
  if (arrivalIso && departureIso) {
    return formatDays(arrivalIso, departureIso);
  }
  if (arrivalIso) {
    return t('trip.arrives', { day: formatShortDay(arrivalIso) });
  }
  return departureIso ? t('trip.departs', { day: formatShortDay(departureIso) }) : null;
}

/** "Sa., 14. Juli, 09:00" */
export function formatDateTime(iso: string): string {
  return format({ weekday: 'short', day: 'numeric', month: 'long', hour: 'numeric', minute: '2-digit' }).format(new Date(iso));
}

/** "Sa., 14. Juli" */
export function formatDay(iso: string): string {
  return format({ weekday: 'short', day: 'numeric', month: 'long' }).format(new Date(iso));
}

/** "09:00" */
export function formatTime(iso: string): string {
  return format({ hour: 'numeric', minute: '2-digit' }).format(new Date(iso));
}

const MS_PER_DAY = 24 * 60 * 60 * 1000;

/** Calendar days between two dates in the system time zone. */
function daysBetween(from: Date, to: Date): number {
  const a = Date.UTC(from.getFullYear(), from.getMonth(), from.getDate());
  const b = Date.UTC(to.getFullYear(), to.getMonth(), to.getDate());
  return Math.round((b - a) / MS_PER_DAY);
}

/** Day of the trip and its length in days, e.g. { day: 3, of: 15 } on the third day. */
export function tripDay(startIso: string, endIso: string, now: Date = new Date()) {
  const start = new Date(startIso);
  return { day: daysBetween(start, now) + 1, of: daysBetween(start, new Date(endIso)) + 1 };
}

/** "heute", "morgen", "in 34 Tagen" until the start, null once it has started. */
export function untilStart(startIso: string, now: Date = new Date()): string | null {
  const days = daysBetween(now, new Date(startIso));
  if (days < 0 || new Date(startIso) <= now) {
    return null;
  }
  if (days === 0) {
    return t('time.today');
  }
  return days === 1 ? t('time.tomorrow') : t('time.inDays', { count: days });
}

/** "vor 20 Min.", "vor 3 Std.", "vor 2 Tagen" */
export function formatAgo(iso: string, now: Date = new Date()): string {
  const minutes = Math.max(0, Math.round((now.getTime() - new Date(iso).getTime()) / 60000));
  if (minutes < 1) {
    return t('time.justNow');
  }
  if (minutes < 60) {
    return t('time.minutesAgo', { minutes });
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return t('time.hoursAgo', { hours });
  }
  return t('time.daysAgo', { count: Math.round(hours / 24) });
}
