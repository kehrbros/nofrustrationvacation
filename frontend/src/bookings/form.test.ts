import { describe, expect, it } from 'vitest';

import { emptyForm, formFromBooking, formFromDraft, toInput, validateForm } from './form';
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
    // check-in time missing: taken from the house rules and to be checked
    expect(form.checkInTime).toBe('14:00');
    expect([...uncertain].sort()).toEqual(['checkInTime', 'price']);
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
