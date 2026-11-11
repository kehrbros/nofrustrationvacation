import { useState } from 'react';

import { t } from '../i18n';
import { forgetTripGuest, rememberTripGuest, TripsApiError } from '../trips/tripsApi';
import type { TripDetail } from '../trips/model';
import { rememberedHolder, rememberHolder, type Holder } from './alternativesApi';

export type HolderField = keyof Holder;
export type HolderErrors = Partial<Record<HolderField, string>>;

const FIELDS: HolderField[] = ['firstName', 'lastName', 'email', 'phone'];
const MAX_LENGTH: Record<HolderField, number> = { firstName: 100, lastName: 100, email: 255, phone: 64 };
// same rules as the backend (AlternativeReservations, TripLogic), so that a booking is not refused for them
const REQUIRED = {
  firstName: () => t('alternatives.guestError.firstNameRequired'),
  lastName: () => t('alternatives.guestError.lastNameRequired'),
  email: () => t('alternatives.guestError.emailRequired'),
  phone: () => t('alternatives.guestError.phoneRequired'),
} satisfies Record<HolderField, () => string>;
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const PHONE = /^\+?[0-9 ()/-]{6,30}$/;

export const EMPTY_HOLDER: Holder = { firstName: '', lastName: '', email: '', phone: '' };

/** What is wrong with the guest data, per field; empty if the hotel can be booked with it. */
export function holderErrors(holder: Holder): HolderErrors {
  const errors: HolderErrors = {};
  for (const field of FIELDS) {
    const value = holder[field].trim();
    if (!value) {
      errors[field] = REQUIRED[field]();
    } else if (value.length > MAX_LENGTH[field]) {
      errors[field] = t('alternatives.guestError.tooLong');
    }
  }
  if (!errors.email && !EMAIL.test(holder.email.trim())) {
    errors.email = t('alternatives.guestError.emailInvalid');
  }
  if (!errors.phone && !PHONE.test(holder.phone.trim())) {
    errors.phone = t('alternatives.guestError.phoneInvalid');
  }
  return errors;
}

export function holderComplete(holder: Holder): boolean {
  return Object.keys(holderErrors(holder)).length === 0;
}

/** The guest field the backend refused (problem detail "field"), null if the input as a whole was wrong. */
export function failedHolderField(failure: unknown): HolderField | null {
  const field = failure instanceof TripsApiError ? failure.body?.field : undefined;
  return typeof field === 'string' && (FIELDS as string[]).includes(field) ? field as HolderField : null;
}

/**
 * Guest data of a booking sheet: prefilled from the trip (remembered for it) or this device, errors shown once a
 * field was left or the user tried to continue, and the backend's verdict on a field.
 */
export function useHolder(trip: TripDetail, reload: () => void) {
  const [holder, setHolderState] = useState<Holder>(() => trip.guest ?? rememberedHolder() ?? EMPTY_HOLDER);
  const [rememberForTrip, setRememberForTrip] = useState(trip.guest !== null);
  const [touched, setTouched] = useState<Partial<Record<HolderField, boolean>>>({});
  const [submitted, setSubmitted] = useState(false);
  const [serverField, setServerField] = useState<HolderField | null>(null);

  const all = holderErrors(holder);
  const errors: HolderErrors = {};
  for (const field of FIELDS) {
    if (all[field] && (submitted || touched[field])) {
      errors[field] = all[field];
    }
  }
  if (serverField && !errors[serverField]) {
    errors[serverField] = t('alternatives.guestError.server');
  }

  return {
    holder,
    errors,
    rememberForTrip,
    setRememberForTrip,
    setHolder: (next: Holder) => {
      if (serverField && next[serverField] !== holder[serverField]) {
        setServerField(null);
      }
      setHolderState(next);
    },
    touch: (field: HolderField) => setTouched((current) => ({ ...current, [field]: true })),
    /** Before continuing: true if the data can be sent, otherwise all errors are shown. */
    check: (): boolean => {
      setSubmitted(true);
      return holderComplete(holder);
    },
    /** A refused booking: marks the guest field the backend named, true if it named one. */
    refused: (failure: unknown): boolean => {
      const field = failedHolderField(failure);
      setServerField(field);
      return field !== null;
    },
    /** After the price is secured: remembers the guest on this device and, if asked, for the trip. */
    saved: () => {
      rememberHolder(holder);
      const guest = { firstName: holder.firstName.trim(), lastName: holder.lastName.trim(), email: holder.email.trim(), phone: holder.phone.trim() };
      const same = trip.guest && FIELDS.every((field) => trip.guest![field] === guest[field]);
      if (rememberForTrip && !same) {
        rememberTripGuest(trip.id, guest).then(reload, () => undefined);
      } else if (!rememberForTrip && trip.guest) {
        forgetTripGuest(trip.id).then(reload, () => undefined);
      }
    },
  };
}

export type HolderState = ReturnType<typeof useHolder>;
