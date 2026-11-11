import { describe, expect, it } from 'vitest';

import type { Criteria } from './alternativesApi';
import { addDays, dateProblem, earliestCheckOut, fromEarliest } from './stayDates';

const criteria = (patch: Partial<Criteria>): Criteria => ({
  latitude: 1, longitude: 2, placeName: null, radiusMeters: 5000, checkIn: '2026-11-10', checkOut: '2026-11-12',
  earliestCheckIn: '2026-11-11', guests: 2, rooms: 1, maxPrice: '300.00', currency: 'EUR', excludeHotelId: null,
  minStars: null, missing: [], ...patch,
});

describe('stay dates', () => {
  it('adds days across month ends', () => {
    expect(addDays('2026-11-30', 1)).toBe('2026-12-01');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
  });

  it('accepts dates from the earliest day on', () => {
    expect(dateProblem(criteria({ checkIn: '2026-11-11' }))).toBeNull();
    expect(dateProblem(criteria({ checkIn: '' }))).toBeNull();
  });

  it('names a stay that started already and one that is over', () => {
    expect(dateProblem(criteria({}))).toBe('startsEarlier');
    expect(dateProblem(criteria({ earliestCheckIn: '2026-11-12' }))).toBe('over');
  });

  it('moves check-in to the earliest day and reduces the price per night', () => {
    const moved = fromEarliest(criteria({}));
    expect(moved.checkIn).toBe('2026-11-11');
    expect(moved.checkOut).toBe('2026-11-12');
    expect(moved.maxPrice).toBe('150.00');
    expect(fromEarliest(criteria({ maxPrice: '' })).maxPrice).toBe('');
  });

  it('offers check-out from the day after check-in or the earliest day', () => {
    expect(earliestCheckOut(criteria({}))).toBe('2026-11-12');
    expect(earliestCheckOut(criteria({ checkIn: '2026-11-14' }))).toBe('2026-11-15');
    expect(earliestCheckOut(criteria({ checkIn: '' }))).toBe('2026-11-12');
  });
});
