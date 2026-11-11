import { describe, expect, it } from 'vitest';

import { TripsApiError } from '../trips/tripsApi';
import { failedHolderField, holderComplete, holderErrors } from './holder';

const VALID = { firstName: 'Max', lastName: 'Muster', email: 'max@example.org', phone: '+49 170 1234567' };

describe('holderErrors', () => {
  it('accepts complete guest data', () => {
    expect(holderErrors(VALID)).toEqual({});
    expect(holderComplete(VALID)).toBe(true);
  });

  it('names every missing field', () => {
    expect(Object.keys(holderErrors({ firstName: ' ', lastName: '', email: '', phone: '' })))
      .toEqual(['firstName', 'lastName', 'email', 'phone']);
  });

  it('checks email and phone like the backend', () => {
    const errors = holderErrors({ ...VALID, email: 'max@example', phone: 'call me' });
    expect(errors.email).toBe('Das ist keine gültige E-Mail-Adresse, z. B. name@beispiel.de.');
    expect(errors.phone).toContain('+49 170 1234567');
    expect(errors.firstName).toBeUndefined();
  });
});

describe('failedHolderField', () => {
  it('reads the field of the problem details', () => {
    expect(failedHolderField(new TripsApiError(400, 'INVALID_BOOKING_INPUT', { field: 'phone' }))).toBe('phone');
    expect(failedHolderField(new TripsApiError(400, 'INVALID_BOOKING_INPUT', {}))).toBeNull();
    expect(failedHolderField(new Error('x'))).toBeNull();
  });
});
