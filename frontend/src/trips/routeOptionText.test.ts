import { describe, expect, it } from 'vitest';

import type { RouteOption, Stop, TripDetail } from './model';
import { formatDistance, formatDuration, optionTitle, slowerBy, substituteDeparture } from './routeOptionText';

const option = (values: Partial<RouteOption>): RouteOption => ({
  rank: 1,
  labels: [],
  via: null,
  departureAt: '2027-07-14T09:00:00+02:00',
  arrivalAt: '2027-07-14T12:40:00+02:00',
  distanceMeters: 250_000,
  durationSeconds: 13_200,
  delaySeconds: 0,
  geometry: [],
  waypoints: [],
  conflicts: [],
  ...values,
});

const stop = (values: Partial<Stop>): Stop => ({
  id: 1,
  position: 0,
  name: 'Wellington',
  latitude: -41.3,
  longitude: 174.8,
  arrivalAt: null,
  departureAt: null,
  ...values,
});

const trip = { startAt: '2027-07-12T00:00:00+02:00' } as TripDetail;

describe('texts of route suggestions', () => {
  it('formats durations and distances', () => {
    expect(formatDuration(13_200)).toBe('3 h 40 min');
    expect(formatDuration(2_700)).toBe('45 min');
    expect(formatDistance(4_230)).toBe('4,2 km');
    expect(formatDistance(128_400)).toBe('128 km');
  });

  it('names options by their labels and main road', () => {
    expect(optionTitle(option({ labels: ['FASTEST', 'CURRENT'], via: 'SH 1' }))).toBe('Schnellste · über SH 1');
    expect(optionTitle(option({ labels: ['SHORTEST'] }))).toBe('Kürzeste');
    expect(optionTitle(option({ rank: 2, via: 'SH 7' }))).toBe('Alternative über SH 7');
    expect(optionTitle(option({ rank: 3 }))).toBe('Alternative 3');
  });

  it('compares the arrival with the fastest option', () => {
    const fastest = option({});
    expect(slowerBy(option({ arrivalAt: '2027-07-14T12:58:00+02:00' }), fastest)).toBe('+18 min');
    expect(slowerBy(fastest, fastest)).toBeNull();
  });
});

describe('substitute departure when the start stop has none', () => {
  it('is not needed with a departure', () => {
    expect(substituteDeparture(trip, stop({ departureAt: '2027-07-14T09:00:00+02:00' }))).toBeNull();
  });

  it('takes the arrival at the stop, otherwise 09:00 on the first day of the trip', () => {
    expect(substituteDeparture(trip, stop({ arrivalAt: '2027-07-13T18:00:00+02:00' }))).toBe('2027-07-13T18:00:00+02:00');
    expect(substituteDeparture(trip, stop({}))).toBe('2027-07-12T09:00:00+02:00');
  });
});
