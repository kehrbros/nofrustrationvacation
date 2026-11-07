import { describe, expect, it } from 'vitest';

import { formatShortDate, proposalTexts } from './proposalText';
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
