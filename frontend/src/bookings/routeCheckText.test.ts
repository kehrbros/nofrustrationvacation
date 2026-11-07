import { describe, expect, it } from 'vitest';

import { formatTime } from '../trips/time';
import type { RouteCheck } from './alternativesApi';
import { routeCheckText } from './routeCheckText';

const check = (values: Partial<RouteCheck>): RouteCheck => ({
  otherPlace: true,
  distanceMeters: 26_000,
  routeId: 7,
  arrivalAt: '2027-07-12T19:40:00+02:00',
  checkInUntil: '2027-07-12T22:00:00+02:00',
  late: false,
  blocked: false,
  delaySeconds: 0,
  problem: null,
  ...values,
});

describe('routeCheckText', () => {
  it('says nothing within the same place or without a check', () => {
    expect(routeCheckText(null)).toBeNull();
    expect(routeCheckText(check({ otherPlace: false }))).toBeNull();
  });

  it('announces the move and the arrival before the check-in ends', () => {
    const text = routeCheckText(check({ delaySeconds: 1500 }))!;
    expect(text.move).toBe('26 km vom bisherigen Stop entfernt. Nach der Buchung wird der Stop dorthin verlegt und '
      + 'die Route neu geplant.');
    expect(text.arrival).toBe(`Ankunft ca. ${formatTime('2027-07-12T19:40:00+02:00')} (inkl. 25 min Verzögerung), `
      + `Check-in bis ${formatTime('2027-07-12T22:00:00+02:00')}`);
    expect(text.warning).toBeNull();
  });

  it('warns about a late arrival and about a closure', () => {
    expect(routeCheckText(check({ late: true }))!.warning).toContain('nach dem Check-in-Ende');
    expect(routeCheckText(check({ late: true, blocked: true }))!.warning).toBe(
      'Auch der beste Weg dorthin führt durch eine Sperrung.');
  });

  it('only announces the move if the way could not be checked', () => {
    const text = routeCheckText(check({ arrivalAt: null, problem: 'UNDER_WAY' }))!;
    expect(text.arrival).toBeNull();
    expect(text.warning).toBeNull();
  });
});
