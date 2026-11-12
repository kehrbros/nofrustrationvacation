import { describe, expect, it } from 'vitest';

import { isAppBooked, isFreeToCancel } from './appBookings';
import type { Booking } from './model';

function booking(patch: Partial<Booking>): Booking {
  return {
    id: 1, stopId: 2, type: 'ACCOMMODATION', status: 'CONFIRMED', source: 'SEARCH', title: 'Hotel Sole',
    provider: 'LiteAPI', reference: 'HC-77', manageUrl: null, startsAt: null, endsAt: null, price: '165.00',
    currency: 'EUR', cancellationDeadline: '2027-07-11T12:00:00+02:00', notes: null, accommodation: null, ...patch,
  };
}

const BEFORE = new Date('2027-07-10T12:00:00+02:00').getTime();
const AFTER = new Date('2027-07-11T12:00:01+02:00').getTime();

describe('app bookings', () => {
  it('only bookings made in the app and not cancelled', () => {
    expect(isAppBooked(booking({}))).toBe(true);
    expect(isAppBooked(booking({ source: 'DOCUMENT' }))).toBe(false);
    expect(isAppBooked(booking({ status: 'CANCELLED' }))).toBe(false);
  });

  it('free to cancel until the deadline', () => {
    expect(isFreeToCancel(booking({}), BEFORE)).toBe(true);
    expect(isFreeToCancel(booking({}), AFTER)).toBe(false);
    expect(isFreeToCancel(booking({ cancellationDeadline: null }), BEFORE)).toBe(false);
    expect(isFreeToCancel(booking({ source: 'MANUAL' }), BEFORE)).toBe(false);
  });
});
