import { describe, expect, it } from 'vitest';

import { formatShortDate, planStepTexts, proposalTexts } from './proposalText';
import type { Proposal } from './proposalsApi';

const setting = { fromName: 'Newtown', targetName: 'Thorndon', targetStayTitle: 'Hotel Adler' };

function proposal(patch: Partial<Proposal>): Proposal {
  return {
    rank: 1,
    kind: 'KEEP_ROUTE',
    via: null,
    departureAt: null,
    arrivalAt: null,
    distanceMeters: null,
    durationSeconds: null,
    delaySeconds: null,
    meetsDeadline: null,
    conflictCount: 0,
    stay: null,
    plan: null,
    steps: [],
    ...patch,
  };
}

const stay = {
  hotelId: 'lp1', offerId: 'o1', hotelName: 'Te Aro Lodge', latitude: -41.29, longitude: 174.77, place: 'Te Aro',
  distanceMeters: 1200, checkIn: '2027-07-12', checkOut: '2027-07-13', guests: 2, rooms: 1, price: '150.00',
  currency: 'EUR', refundable: true, freeCancellationUntil: null, replacesBookingId: 11,
};

describe('proposalTexts', () => {
  it('names the road of a detour and what is left on the way', () => {
    const texts = proposalTexts(proposal({ kind: 'REROUTE', via: 'SH 2', delaySeconds: 900, distanceMeters: 45_000,
      conflictCount: 1 }), setting);
    expect(texts.title).toBe('Umfahrung über SH 2');
    expect(texts.detail).toContain('15 Min. später');
    expect(texts.detail).toContain('45 km');
    expect(texts.detail).toContain('noch 1 Hinweis unterwegs');
    expect(texts.effect).toBe('Die Route nach Thorndon wird über SH 2 neu geplant.');
  });

  it('keeping the route while closed means waiting', () => {
    expect(proposalTexts(proposal({}), setting).detail).toBe('Warten, bis die Strecke wieder frei ist');
  });

  it('a night on the way names place, hotel, price and the late arrival', () => {
    const texts = proposalTexts(proposal({ kind: 'STOPOVER', stay: { ...stay, replacesBookingId: null } }), setting);
    expect(texts.title).toBe('Übernachten in Te Aro');
    expect(texts.detail.replace(/\s/g, ' ')).toBe('Te Aro Lodge · 150,00 € · kostenlos stornierbar · weiter am 13.07.');
    expect(texts.effect).toContain('vor Thorndon');
    expect(texts.effect).toContain('„Hotel Adler“ erwartet dich dann einen Tag später');
  });

  it('another accommodation puts the old one on the to-do list', () => {
    const texts = proposalTexts(proposal({ kind: 'TARGET_STAY', stay }), setting);
    expect(texts.title).toBe('Andere Unterkunft: Te Aro Lodge');
    expect(texts.detail).toContain('1,2 km von Te Aro');
    expect(texts.effect).toContain('„Hotel Adler“ kommt danach auf deine To-do-Liste');
  });

  it('formats short dates', () => {
    expect(formatShortDate('2027-07-05')).toBe('05.07.');
  });
});

describe('plans', () => {
  const night = { ...stay, hotelName: 'Geraldine Motel', place: 'Geraldine', price: '128.00', currency: 'NZD',
    replacesBookingId: null };
  const plan = proposal({
    kind: 'PLAN',
    arrivalAt: '2027-07-13T12:30:00+12:00',
    plan: { deadline: null, reachesTarget: true, extraNights: 1, extraCost: '128.00', costCurrency: 'NZD',
      affectsFollowing: false, newTargetName: null },
    steps: [
      { kind: 'DRIVE', routeKind: 'KEEP_ROUTE', via: null, departureAt: '2027-07-12T08:00:00+12:00',
        arrivalAt: '2027-07-12T19:00:00+12:00', waitedFor: null, distanceMeters: null, placeName: 'Geraldine',
        arrivalOn: null, stay: null },
      { kind: 'OVERNIGHT', routeKind: null, via: null, departureAt: null, arrivalAt: null, waitedFor: null,
        distanceMeters: null, placeName: 'Geraldine', arrivalOn: null, stay: night },
      { kind: 'CONTINUE', routeKind: 'REROUTE', via: 'SH 8', departureAt: '2027-07-13T09:00:00+12:00',
        arrivalAt: '2027-07-13T12:30:00+12:00', waitedFor: null, distanceMeters: null, placeName: null,
        arrivalOn: null, stay: null },
      { kind: 'KEEP_BOOKING', routeKind: null, via: null, departureAt: null, arrivalAt: null, waitedFor: null,
        distanceMeters: null, placeName: null, arrivalOn: '2027-07-13', stay: null },
    ],
  });

  it('names the night and the price', () => {
    const texts = proposalTexts(plan, setting);
    expect(texts.title).toContain('Geraldine');
    expect(texts.detail).toContain('128');
    expect(texts.effect).toContain('PayPal');
  });

  it('lists every step in order', () => {
    const lines = planStepTexts(plan, setting);
    expect(lines).toHaveLength(4);
    expect(lines[0]).toContain('Geraldine');
    expect(lines[1]).toContain('Geraldine Motel');
    expect(lines[3]).toContain('Hotel Adler');
  });
});
