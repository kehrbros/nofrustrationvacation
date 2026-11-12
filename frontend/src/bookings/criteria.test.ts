import { describe, expect, it } from 'vitest';

import type { Criteria } from './alternativesApi';
import { canSearch, criteriaErrors } from './criteria';

const valid: Criteria = {
  latitude: -45.03,
  longitude: 168.66,
  placeName: 'Queenstown',
  radiusMeters: 5000,
  checkIn: '2027-07-12',
  checkOut: '2027-07-15',
  earliestCheckIn: '2027-07-10',
  guests: 2,
  rooms: 1,
  maxPrice: '',
  currency: 'NZD',
  excludeHotelId: null,
  minStars: null,
  missing: [],
};

describe('criteriaErrors', () => {
  it('accepts a complete search', () => {
    expect(criteriaErrors(valid)).toEqual({});
    expect(canSearch(valid)).toBe(true);
  });

  it('names each missing or wrong criterion at its field', () => {
    const errors = criteriaErrors({
      ...valid, latitude: null, longitude: null, checkIn: '', checkOut: '', guests: null, maxPrice: 'viel', currency: 'N',
    });
    expect(Object.keys(errors).sort()).toEqual(['checkIn', 'checkOut', 'currency', 'guests', 'location', 'maxPrice']);
    expect(errors.checkOut).toBe('Bitte die Abreise angeben.');
  });

  it('checks dates and rooms like the backend', () => {
    expect(criteriaErrors({ ...valid, checkOut: '2027-07-12' }).checkOut).toMatch(/nach der Anreise/);
    expect(criteriaErrors({ ...valid, guests: 1, rooms: 2 }).guests).toMatch(/je Zimmer/);
    expect(criteriaErrors({ ...valid, rooms: 10 }).rooms).toMatch(/1 bis 9/);
  });

  it('does not search before the earliest bookable day', () => {
    expect(criteriaErrors({ ...valid, checkIn: '2027-07-08' })).toEqual({});
    expect(canSearch({ ...valid, checkIn: '2027-07-08' })).toBe(false);
  });
});
