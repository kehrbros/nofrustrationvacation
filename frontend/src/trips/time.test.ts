import { describe, expect, it } from 'vitest';

import { endOfDayIso, formatAgo, formatPeriod, stopDays, localDateTimeIso, startOfDayIso, toOffsetIso, tripDay, untilStart } from './time';

describe('points in time with the offset of the system time zone (Europe/Berlin in tests)', () => {
  it('uses the offset valid on that day', () => {
    expect(toOffsetIso(new Date(2027, 6, 12, 9, 30))).toBe('2027-07-12T09:30:00+02:00');
    expect(toOffsetIso(new Date(2028, 1, 10, 0, 0))).toBe('2028-02-10T00:00:00+01:00');
  });

  it('turns the days of a trip into begin and inclusive end', () => {
    expect(startOfDayIso('2027-07-12')).toBe('2027-07-12T00:00:00+02:00');
    expect(endOfDayIso('2027-07-26')).toBe('2027-07-26T23:59:59+02:00');
    expect(startOfDayIso('')).toBeNull();
  });

  it('reads datetime-local inputs', () => {
    expect(localDateTimeIso('2027-07-14T09:00')).toBe('2027-07-14T09:00:00+02:00');
    expect(localDateTimeIso('')).toBeNull();
  });
});

describe('formatting', () => {
  it('shortens periods within a month and spells out longer ones', () => {
    expect(formatPeriod('2027-07-12T00:00:00+02:00', '2027-07-26T23:59:59+02:00')).toBe('12.–26. Juli 2027');
    expect(formatPeriod('2027-07-30T00:00:00+02:00', '2027-08-02T23:59:59+02:00')).toBe('30. Juli – 2. August 2027');
    expect(formatPeriod('2027-12-30T00:00:00+01:00', '2028-01-02T23:59:59+01:00')).toBe('30. Dezember 2027 – 2. Januar 2028');
  });

  it('shows the days of a stop without times', () => {
    expect(stopDays('2027-07-09T22:20:00+02:00', '2027-07-11T22:20:00+02:00')).toBe('9.–11. Juli');
    expect(stopDays('2027-07-30T18:00:00+02:00', '2027-08-02T10:00:00+02:00')).toBe('30. Juli – 2. Aug.');
    expect(stopDays('2027-07-09T22:20:00+02:00', null)).toBe('ab 9. Juli');
    expect(stopDays(null, '2027-07-11T10:00:00+02:00')).toBe('bis 11. Juli');
    expect(stopDays(null, null)).toBeNull();
  });

  it('shows points in time entered elsewhere in the system time zone', () => {
    // entered in Lisbon (+01:00 in summer), shown in Berlin
    expect(formatPeriod('2027-07-12T00:00:00+01:00', '2027-07-12T22:00:00+01:00')).toBe('12. Juli 2027');
    expect(startOfDayIso('2027-07-12')).toBe('2027-07-12T00:00:00+02:00');
  });

  it('counts the days of a trip and until it starts', () => {
    const now = new Date(2027, 6, 14, 12, 0);
    expect(tripDay('2027-07-12T00:00:00+02:00', '2027-07-26T23:59:59+02:00', now)).toEqual({ day: 3, of: 15 });
    expect(untilStart('2027-08-17T00:00:00+02:00', now)).toBe('in 34 Tagen');
    expect(untilStart('2027-07-15T00:00:00+02:00', now)).toBe('morgen');
    expect(untilStart('2027-07-12T00:00:00+02:00', now)).toBeNull();
  });

  it('tells how long ago something was updated', () => {
    const now = new Date('2027-07-14T10:00:00+02:00');
    expect(formatAgo('2027-07-14T09:40:00+02:00', now)).toBe('vor 20 Min.');
    expect(formatAgo('2027-07-14T07:00:00+02:00', now)).toBe('vor 3 Std.');
  });
});
