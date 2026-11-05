import { describe, expect, it } from 'vitest';

import { emptyForm, formFromBooking, formFromDraft, liteApiHotelId, stayTimes, toInput, validateForm, withStayDefaults } from './form';
import { formatLocalDateTime, formatStay, nights } from './localTime';
import type { AccommodationDraft, Booking } from './model';

const draft: AccommodationDraft = {
  found: true,
  name: 'Hotel Adler',
  addressLine: 'Via Roma 1, Rom',
  countryCode: 'IT',
  phone: null,
  email: null,
  website: null,
  provider: 'Booking.com',
  reference: '4711',
  manageUrl: null,
  checkInDate: '2027-07-12',
  checkInTime: null,
  checkOutDate: '2027-07-15',
  checkOutTime: '10:30:00',
  checkInFrom: '14:00:00',
  checkInUntil: null,
  checkOutUntil: null,
  guests: 2,
  rooms: null,
  price: '412.50',
  currency: 'EUR',
  cancellationDeadline: '2027-07-10T18:00:00',
  uncertainFields: ['price', 'unknownField'],
};

describe('formFromDraft', () => {
  it('fills what was read and marks what to check', () => {
    const { form, filled, uncertain } = formFromDraft(draft);
    expect(form.name).toBe('Hotel Adler');
    expect(form.checkOutTime).toBe('10:30');
    expect(form.guests).toBe('2');
    expect(form.cancellationDeadline).toBe('2027-07-10T18:00');
    expect(filled.has('reference')).toBe(true);
    expect(filled.has('phone')).toBe(false);
    // times of the stay are not shown: no booked check-in time, the default stays, the house rules win in toInput
    expect(form.checkInTime).toBe('15:00');
    expect(form.checkInFrom).toBe('14:00');
    expect([...uncertain].sort()).toEqual(['price']);
  });

  it('falls back to default times', () => {
    const { form, uncertain } = formFromDraft({ ...draft, checkInFrom: null, checkOutTime: null, checkInDate: null });
    expect(form.checkInTime).toBe('15:00');
    expect(form.checkOutTime).toBe('10:00');
    expect(uncertain.has('checkInDate')).toBe(true);
  });
});

describe('validateForm and toInput', () => {
  const valid = { ...formFromDraft(draft).form, price: '412,5', manageUrl: 'https://booking.com/x' };

  it('sends local times without offset and a normalized price', () => {
    expect(validateForm(valid)).toBeNull();
    const input = toInput(valid, 'DOCUMENT');
    expect(input.checkIn).toBe('2027-07-12T14:00:00');
    expect(input.checkOut).toBe('2027-07-15T10:30:00');
    expect(input.price).toBe('412.50');
    expect(input.currency).toBe('EUR');
    expect(input.cancellationDeadline).toBe('2027-07-10T18:00:00');
    expect(input.phone).toBeNull();
    expect(input.fallbackTimeZone).toBe('Europe/Berlin');
  });

  it('rejects incomplete or inconsistent input', () => {
    expect(validateForm(emptyForm())).toMatch(/Namen/);
    expect(validateForm({ ...valid, checkOutDate: '2027-07-11' })).toMatch(/nach der Anreise/);
    expect(validateForm({ ...valid, manageUrl: 'javascript:alert(1)' })).toMatch(/https/);
    expect(validateForm({ ...valid, price: 'viel' })).toMatch(/Preis/);
  });
});

describe('stay defaults and times', () => {
  it('fills empty dates and guests from stop and trip only', () => {
    const form = withStayDefaults(emptyForm(), { checkInDate: '2027-07-12', checkOutDate: '2027-07-15', guests: 3 });
    expect([form.checkInDate, form.checkOutDate, form.guests]).toEqual(['2027-07-12', '2027-07-15', '3']);
    const read = withStayDefaults({ ...emptyForm(), checkInDate: '2027-08-01', guests: '2' }, { checkInDate: '2027-07-12', checkOutDate: '2027-07-15', guests: 3 });
    // departure before the arrival read from the confirmation is not taken
    expect([read.checkInDate, read.checkOutDate, read.guests]).toEqual(['2027-08-01', '', '2']);
  });

  it('takes the house rules as times of the stay, else the booked time or the default', () => {
    expect(stayTimes(emptyForm())).toEqual({ checkIn: '15:00', checkOut: '10:00' });
    expect(stayTimes({ ...emptyForm(), checkInFrom: '14:00', checkOutTime: '11:30', checkOutUntil: '' })).toEqual({ checkIn: '14:00', checkOut: '11:30' });
    const input = toInput({ ...emptyForm(), name: 'X', checkInDate: '2027-07-12', checkOutDate: '2027-07-13', checkOutUntil: '12:00' }, 'MANUAL');
    expect([input.checkIn, input.checkOut]).toEqual(['2027-07-12T15:00:00', '2027-07-13T12:00:00']);
  });

  it('reads the LiteAPI hotel id from the place reference', () => {
    expect(liteApiHotelId({ ...emptyForm(), placeRef: 'liteapi:lp42' })).toBe('lp42');
    expect(liteApiHotelId({ ...emptyForm(), placeRef: 'osm:node/1' })).toBeNull();
  });
});

describe('local time of the place', () => {
  const booking: Booking = {
    id: 1,
    stopId: 2,
    type: 'ACCOMMODATION',
    status: 'CONFIRMED',
    source: 'DOCUMENT',
    title: 'Hotel Adler',
    provider: null,
    reference: null,
    manageUrl: null,
    // check-in in New York, shown as written there even though the tests run in Europe/Berlin
    startsAt: '2027-07-12T15:00:00-04:00',
    endsAt: '2027-07-15T10:00:00-04:00',
    price: null,
    currency: null,
    cancellationDeadline: null,
    notes: null,
    accommodation: null,
  };

  it('formats without converting into the system zone', () => {
    expect(formatLocalDateTime(booking.startsAt!)).toBe('Mo., 12. Juli · 15:00');
    expect(formatStay(booking.startsAt!, booking.endsAt!)).toBe('12.–15. Juli');
    expect(nights(booking.startsAt!, booking.endsAt!)).toBe(3);
  });

  it('round-trips into the form', () => {
    const form = formFromBooking(booking);
    expect(form.checkInDate).toBe('2027-07-12');
    expect(form.checkInTime).toBe('15:00');
    expect(form.name).toBe('Hotel Adler');
  });
});
