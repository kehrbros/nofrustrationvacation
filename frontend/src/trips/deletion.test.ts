import { describe, expect, it } from 'vitest';

import type { Booking } from '../bookings/model';
import { stopDeletionText, tripDeletionText } from './deletion';
import type { Stop, TripDetail } from './model';

function stop(id: number, name: string): Stop {
  return { id, position: id, name, latitude: null, longitude: null, arrivalAt: null, departureAt: null };
}

const trip: TripDetail = {
  id: 1,
  name: 'Italien',
  startAt: '2027-07-10T00:00:00+02:00',
  endAt: '2027-07-20T00:00:00+02:00',
  phase: 'UPCOMING',
  travelers: null,
  hasCoverImage: false,
  stops: [stop(1, 'Rom'), stop(2, 'Florenz'), stop(3, 'Venedig')],
  routes: [],
};
const booking = (title: string) => ({ id: 1, stopId: 2, title }) as Booking;

describe('stopDeletionText', () => {
  it('names the booking deleted with it and the new route between the neighbours', () => {
    expect(stopDeletionText(trip, 2, [booking('Hotel Adler')])).toBe(
      'Die Buchung „Hotel Adler“ wird mitgelöscht. Zwischen Rom und Venedig wird eine neue Route geplant.',
    );
  });

  it('counts several bookings', () => {
    expect(stopDeletionText(trip, 2, [booking('A'), booking('B')])).toContain('Die 2 Buchungen am Stop');
  });

  it('drops the route at the ends of the trip', () => {
    expect(stopDeletionText(trip, 1, [])).toBe('Die Route nach Florenz entfällt.');
    expect(stopDeletionText(trip, 3, [])).toBe('Die Route von Florenz entfällt.');
  });

  it('falls back for the only stop', () => {
    expect(stopDeletionText({ ...trip, stops: [stop(1, 'Rom')] }, 1, [])).toBe('Der Stop wird aus der Reise entfernt.');
  });
});

describe('tripDeletionText', () => {
  it('lists stops and bookings', () => {
    expect(tripDeletionText(trip, [booking('A')])).toBe('3 Stops und 1 Buchung mit allen Routen werden mitgelöscht.');
    expect(tripDeletionText({ ...trip, stops: [] }, [])).toBe('Die Reise wird gelöscht.');
  });
});
