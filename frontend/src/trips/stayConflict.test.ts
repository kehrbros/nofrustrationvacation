import { describe, expect, it } from 'vitest';

import { bookingOutsideStop, outsideBookingLabel, stopBookingsConflict } from './stayConflict';
import { TripsApiError } from './tripsApi';

describe('stopBookingsConflict', () => {
  it('reads the bookings and the extended days from the problem details', () => {
    const failure = new TripsApiError(409, 'STOP_BOOKINGS_CONFLICT', {
      code: 'STOP_BOOKINGS_CONFLICT',
      bookings: [{ bookingId: 7, title: 'Hotel Waterloo', startsAt: '2027-07-12T15:00:00+12:00', endsAt: '2027-07-15T10:00:00+12:00', trimmable: true }],
      extendedArrivalAt: '2027-07-12T12:00:00+12:00',
      extendedDepartureAt: null,
    });

    expect(stopBookingsConflict(failure)).toEqual({
      bookings: [{ bookingId: 7, title: 'Hotel Waterloo', startsAt: '2027-07-12T15:00:00+12:00', endsAt: '2027-07-15T10:00:00+12:00', trimmable: true }],
      extendedArrivalAt: '2027-07-12T12:00:00+12:00',
      extendedDepartureAt: null,
    });
  });

  it('ignores other failures', () => {
    expect(stopBookingsConflict(new TripsApiError(409, 'STOP_IN_USE', { code: 'STOP_IN_USE' }))).toBeNull();
    expect(stopBookingsConflict(new Error('offline'))).toBeNull();
    expect(bookingOutsideStop(new TripsApiError(409, 'STOP_BOOKINGS_CONFLICT', {}))).toBeNull();
  });
});

describe('bookingOutsideStop', () => {
  it('reads the days of the stop and whether the booking can be shortened', () => {
    const failure = new TripsApiError(409, 'BOOKING_OUTSIDE_STOP', {
      stopArrivalAt: '2027-07-12T12:00:00+12:00',
      stopDepartureAt: '2027-07-15T11:00:00+12:00',
      extendedArrivalAt: '2027-07-11T12:00:00+12:00',
      trimmable: false,
    });

    expect(bookingOutsideStop(failure)).toEqual({
      stopArrivalAt: '2027-07-12T12:00:00+12:00',
      stopDepartureAt: '2027-07-15T11:00:00+12:00',
      extendedArrivalAt: '2027-07-11T12:00:00+12:00',
      extendedDepartureAt: null,
      trimmable: false,
    });
  });
});

describe('outsideBookingLabel', () => {
  it('shows the days at the place', () => {
    expect(outsideBookingLabel({ bookingId: 7, title: 'Hotel Waterloo', startsAt: '2027-07-12T15:00:00+12:00', endsAt: '2027-07-15T10:00:00+12:00', trimmable: true }))
      .toBe('Hotel Waterloo · 12.–15. Juli');
  });
});
