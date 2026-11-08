import { locale } from '../i18n';
import { plainSpaces } from '../trips/time';

/**
 * Times of bookings are local at the place (e.g. check-in 15:00 in Rome), whatever zone the phone is in. They come
 * as ISO 8601 with the offset of the place and are shown as written there, never converted into the system zone.
 */

/** Wall clock of an ISO point in time as written, e.g. { date: '2027-07-12', time: '15:00' }. */
export function wallClock(iso: string): { date: string; time: string } {
  return { date: iso.slice(0, 10), time: iso.slice(11, 16) };
}

function asUtcDate(date: string, time = '00:00'): Date {
  const [year, month, day] = date.split('-').map(Number);
  const [hours, minutes] = time.split(':').map(Number);
  return new Date(Date.UTC(year, month - 1, day, hours || 0, minutes || 0));
}

// in the current language, created per call because the language can change
const dayFormat = () =>
  new Intl.DateTimeFormat(locale(), { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });
const dayMonthFormat = () => new Intl.DateTimeFormat(locale(), { day: 'numeric', month: 'short', timeZone: 'UTC' });

/** "Mo., 12. Juli · 15:00" in the local time of the place. */
export function formatLocalDateTime(iso: string): string {
  const { date, time } = wallClock(iso);
  return `${dayFormat().format(asUtcDate(date))} · ${time}`;
}

/** "Mo., 12. Juli", the day at the place. */
export function formatLocalDate(iso: string): string {
  return dayFormat().format(asUtcDate(wallClock(iso).date));
}

/** "12.–15. Juli" or "30. Juli – 2. Aug." for a stay, local at the place. */
export function formatStay(startIso: string, endIso: string): string {
  return plainSpaces(dayMonthFormat().formatRange(asUtcDate(wallClock(startIso).date), asUtcDate(wallClock(endIso).date)));
}

/** Nights between check-in and check-out (calendar days at the place). */
export function nights(startIso: string, endIso: string): number {
  const start = asUtcDate(wallClock(startIso).date).getTime();
  const end = asUtcDate(wallClock(endIso).date).getTime();
  return Math.round((end - start) / (24 * 60 * 60 * 1000));
}

/** IANA zone of the system, e.g. Europe/Berlin. */
export function systemTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

/** "HH:MM:SS" or "HH:MM" → "HH:MM", empty for null. */
export function shortTime(time: string | null | undefined): string {
  return time ? time.slice(0, 5) : '';
}
