import { describe, expect, it } from 'vitest';

import type { Booking } from '../bookings/model';
import { targetAccommodation, tripAlternatives } from './alternatives';
import { consequenceText } from './incidentText';
import type { Route, Stop, TripIncident } from './model';

const consequence = (values: Partial<TripIncident>): TripIncident => ({
  incidentId: 7,
  routeId: 3,
  type: 'CLOSURE',
  description: 'Vollsperrung A22',
  impact: 'BLOCKED',
  delayMinutes: null,
  validFrom: '2027-07-14T06:00:00+02:00',
  validTo: null,
  detectedAt: '2027-07-13T20:00:00+02:00',
  updatedAt: '2027-07-13T20:00:00+02:00',
  category: 'DELAYED',
  match: 'DIRECT',
  assessedDelayMinutes: 90,
  delayAssumed: false,
  expectedArrival: '2027-07-14T14:10:00+02:00',
  deadline: null,
  timesUnconfirmed: false,
  geometry: [],
  resolved: false,
  resolvedAt: null,
  ...values,
});

const route: Route = { id: 3, fromStopId: 1, toStopId: 2 } as Route;
const verona = { id: 2, name: 'Verona' } as Stop;
const booking = (values: Partial<Booking>): Booking =>
  ({ id: 11, stopId: 2, type: 'ACCOMMODATION', status: 'CONFIRMED', title: 'Hotel Arena', startsAt: '2027-07-14T15:00:00+02:00', ...values }) as Booking;

describe('tripAlternatives', () => {
  it('offers a detour and the accommodation search for the booking at the target stop', () => {
    const { alternatives } = tripAlternatives(consequence({}), route, verona, [booking({})]);
    expect(alternatives.map((a) => [a.kind, a.title, a.to])).toEqual([
      ['ROUTE', 'Umfahrung', 'routen/3'],
      ['ACCOMMODATION', 'Andere Unterkunft', 'buchungen/11/alternativen'],
    ]);
  });

  it('has no accommodation alternative without a confirmed accommodation at the target stop', () => {
    const others = [booking({ status: 'CANCELLED' }), booking({ id: 12, type: 'ACTIVITY' }), booking({ id: 13, stopId: 1 })];
    const accommodation = tripAlternatives(consequence({ impact: 'DELAYED' }), route, verona, others).alternatives[1];
    expect(accommodation.to).toBeNull();
    expect(accommodation.detail).toBe('Für Verona ist keine Unterkunft hinterlegt.');
  });

  it('takes the earliest check-in at the target stop', () => {
    const later = booking({ id: 20, startsAt: '2027-07-16T15:00:00+02:00' });
    expect(targetAccommodation([later, booking({})], verona)?.id).toBe(11);
  });
});

describe('consequenceText', () => {
  it('names the delay for DELAYED, estimated when assumed', () => {
    expect(consequenceText(consequence({}), 'Verona').headline).toBe('1 Std. 30 Min. später');
    expect(consequenceText(consequence({ delayAssumed: true }), 'Verona').headline).toBe('Etwa 1 Std. 30 Min. später');
  });

  it('says when the route cannot be passed', () => {
    const text = consequenceText(consequence({ category: 'TOO_LATE', assessedDelayMinutes: null, expectedArrival: null }), 'Verona');
    expect(text.headline).toBe('Hier kommst du nicht durch');
  });
});
