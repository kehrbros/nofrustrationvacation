import { t } from '../i18n';
import { shortTime, systemTimeZone, wallClock } from './localTime';
import type { AccommodationBookingInput, AccommodationDraft, Booking, BookingSource } from './model';

/** State of the accommodation form: what inputs hold (strings), times local at the accommodation. */
export interface AccommodationForm {
  name: string;
  addressLine: string;
  countryCode: string;
  latitude: number | null;
  longitude: number | null;
  /** Origin of the place: liteapi:<hotelId> (chosen from the LiteAPI suggestions) or osm:<type>/<id>. */
  placeRef: string | null;
  /** Name and address of the chosen place, e.g. for a new stop. */
  placeDetail: string;
  phone: string;
  email: string;
  website: string;
  provider: string;
  reference: string;
  manageUrl: string;
  checkInDate: string;
  /** Not shown: start of the stay if checkInFrom is empty (from a confirmation, a stored booking or the default). */
  checkInTime: string;
  checkOutDate: string;
  /** Not shown: end of the stay if checkOutUntil is empty. */
  checkOutTime: string;
  checkInFrom: string;
  checkInUntil: string;
  checkOutUntil: string;
  guests: string;
  rooms: string;
  price: string;
  currency: string;
  /** yyyy-mm-ddTHH:MM (input of type datetime-local) */
  cancellationDeadline: string;
  notes: string;
}

export type FormField = keyof AccommodationForm;

/** Form filled from a confirmation: which fields the assistant filled, which the user should check. */
export interface FilledForm {
  form: AccommodationForm;
  filled: Set<FormField>;
  uncertain: Set<FormField>;
}

/** Check-in and check-out when neither the confirmation nor the house rules name a time. */
export const DEFAULT_CHECK_IN = '15:00';
export const DEFAULT_CHECK_OUT = '10:00';

export function emptyForm(): AccommodationForm {
  return {
    name: '',
    addressLine: '',
    countryCode: '',
    latitude: null,
    longitude: null,
    placeRef: null,
    placeDetail: '',
    phone: '',
    email: '',
    website: '',
    provider: '',
    reference: '',
    manageUrl: '',
    checkInDate: '',
    checkInTime: DEFAULT_CHECK_IN,
    checkOutDate: '',
    checkOutTime: DEFAULT_CHECK_OUT,
    checkInFrom: '',
    checkInUntil: '',
    checkOutUntil: '',
    guests: '',
    rooms: '',
    price: '',
    currency: 'EUR',
    cancellationDeadline: '',
    notes: '',
  };
}

const DRAFT_FIELDS: FormField[] = [
  'name', 'addressLine', 'countryCode', 'phone', 'email', 'website', 'provider', 'reference', 'manageUrl',
  'checkInDate', 'checkInTime', 'checkOutDate', 'checkOutTime', 'checkInFrom', 'checkInUntil', 'checkOutUntil',
  'guests', 'rooms', 'price', 'currency', 'cancellationDeadline',
];

/**
 * Form from what the assistant read. Missing times come from the house rules, else the defaults, and are marked
 * for checking, as are the fields the assistant was unsure about.
 */
export function formFromDraft(draft: AccommodationDraft): FilledForm {
  const form = emptyForm();
  const filled = new Set<FormField>();
  const uncertain = new Set<FormField>(
    draft.uncertainFields.filter((field): field is FormField => (DRAFT_FIELDS as string[]).includes(field)),
  );
  const set = (field: FormField, value: string | number | null | undefined) => {
    if (value !== null && value !== undefined && value !== '') {
      (form[field] as string) = String(value);
      filled.add(field);
    }
  };
  set('name', draft.name);
  set('addressLine', draft.addressLine);
  set('countryCode', draft.countryCode);
  set('phone', draft.phone);
  set('email', draft.email);
  set('website', draft.website);
  set('provider', draft.provider);
  set('reference', draft.reference);
  set('manageUrl', draft.manageUrl);
  set('checkInDate', draft.checkInDate);
  set('checkOutDate', draft.checkOutDate);
  set('checkInFrom', shortTime(draft.checkInFrom));
  set('checkInUntil', shortTime(draft.checkInUntil));
  set('checkOutUntil', shortTime(draft.checkOutUntil));
  set('guests', draft.guests);
  set('rooms', draft.rooms);
  set('price', draft.price);
  set('currency', draft.currency);
  set('cancellationDeadline', draft.cancellationDeadline?.slice(0, 16));

  // times of the stay are not shown; booked times win over the defaults, the house rules over both (see stayTimes)
  form.checkInTime = shortTime(draft.checkInTime) || DEFAULT_CHECK_IN;
  form.checkOutTime = shortTime(draft.checkOutTime) || DEFAULT_CHECK_OUT;
  (['name', 'checkInDate', 'checkOutDate'] as FormField[]).forEach((field) => {
    if (!form[field]) {
      uncertain.add(field);
    }
  });
  return { form, filled, uncertain };
}

/** Form for editing a stored accommodation booking. */
export function formFromBooking(booking: Booking): AccommodationForm {
  const accommodation = booking.accommodation;
  const checkIn = booking.startsAt ? wallClock(booking.startsAt) : { date: '', time: DEFAULT_CHECK_IN };
  const checkOut = booking.endsAt ? wallClock(booking.endsAt) : { date: '', time: DEFAULT_CHECK_OUT };
  return {
    name: accommodation?.name ?? booking.title,
    addressLine: accommodation?.addressLine ?? '',
    countryCode: accommodation?.countryCode ?? '',
    latitude: accommodation?.latitude ?? null,
    longitude: accommodation?.longitude ?? null,
    placeRef: accommodation?.placeRef ?? null,
    placeDetail: accommodation?.addressLine ?? '',
    phone: accommodation?.phone ?? '',
    email: accommodation?.email ?? '',
    website: accommodation?.website ?? '',
    provider: booking.provider ?? '',
    reference: booking.reference ?? '',
    manageUrl: booking.manageUrl ?? '',
    checkInDate: checkIn.date,
    checkInTime: checkIn.time,
    checkOutDate: checkOut.date,
    checkOutTime: checkOut.time,
    checkInFrom: shortTime(accommodation?.checkInFrom),
    checkInUntil: shortTime(accommodation?.checkInUntil),
    checkOutUntil: shortTime(accommodation?.checkOutUntil),
    guests: accommodation?.guests != null ? String(accommodation.guests) : '',
    rooms: accommodation?.rooms != null ? String(accommodation.rooms) : '',
    price: booking.price ?? '',
    currency: booking.currency ?? 'EUR',
    cancellationDeadline: booking.cancellationDeadline ? booking.cancellationDeadline.slice(0, 16) : '',
    notes: booking.notes ?? '',
  };
}

/** What the booking is entered for: dates of the stay at the stop and the travelers of the trip. */
export interface StayDefaults {
  /** yyyy-mm-dd */
  checkInDate?: string | null;
  checkOutDate?: string | null;
  guests?: number | null;
}

/** Fills empty dates and guests from the stop and the trip; what is already in the form stays. */
export function withStayDefaults(form: AccommodationForm, defaults: StayDefaults | undefined): AccommodationForm {
  if (!defaults) {
    return form;
  }
  const next = { ...form };
  if (!next.checkInDate && defaults.checkInDate) {
    next.checkInDate = defaults.checkInDate;
  }
  if (!next.checkOutDate && defaults.checkOutDate && defaults.checkOutDate > next.checkInDate) {
    next.checkOutDate = defaults.checkOutDate;
  }
  if (!next.guests.trim() && defaults.guests) {
    next.guests = String(defaults.guests);
  }
  return next;
}

/**
 * Start and end of the stay (HH:MM, local at the accommodation). The form asks for dates only: the time is the
 * check-in from / check-out until of the house rules, else the booked time or the default.
 */
export function stayTimes(form: AccommodationForm): { checkIn: string; checkOut: string } {
  return {
    checkIn: (form.checkInFrom || form.checkInTime || DEFAULT_CHECK_IN).slice(0, 5),
    checkOut: (form.checkOutUntil || form.checkOutTime || DEFAULT_CHECK_OUT).slice(0, 5),
  };
}

/** LiteAPI hotel id of the chosen accommodation, null if it was not chosen from the LiteAPI suggestions. */
export function liteApiHotelId(form: AccommodationForm): string | null {
  return form.placeRef?.startsWith('liteapi:') ? form.placeRef.slice('liteapi:'.length) : null;
}

const blank = (value: string) => (value.trim() ? value.trim() : null);
const time = (value: string) => (value ? `${value.slice(0, 5)}:00` : null);

/** Fields of the form the backend may name when it refuses a booking (problem detail "field"). */
export const FORM_FIELDS: FormField[] = [
  'name', 'addressLine', 'phone', 'email', 'website', 'provider', 'reference', 'manageUrl', 'checkInDate',
  'checkOutDate', 'guests', 'rooms', 'price', 'currency', 'notes',
];

/** API names of the booking input (BookingLogic) → field of the form; others are named alike. */
export const API_FIELDS: Partial<Record<string, FormField>> = {
  checkIn: 'checkInDate',
  checkOut: 'checkOutDate',
  // not shown on its own, comes with the address
  countryCode: 'addressLine',
  placeRef: 'name',
  latitude: 'name',
};

/** What is wrong with the form, per field (current language, same rules as BookingLogic); empty if it can be saved. */
export function formErrors(form: AccommodationForm): Partial<Record<FormField, string>> {
  const errors: Partial<Record<FormField, string>> = {};
  if (!form.name.trim()) {
    errors.name = t('form.nameMissing');
  }
  if (!form.checkInDate) {
    errors.checkInDate = t('form.checkInMissing');
  }
  if (!form.checkOutDate) {
    errors.checkOutDate = t('form.checkOutMissing');
  } else if (form.checkInDate) {
    const { checkIn, checkOut } = stayTimes(form);
    if (`${form.checkOutDate}T${checkOut}` <= `${form.checkInDate}T${checkIn}`) {
      errors.checkOutDate = t('form.checkOutBeforeCheckIn');
    }
  }
  const price = form.price.trim().replace(',', '.');
  if (price && !/^\d{1,10}(\.\d{1,2})?$/.test(price)) {
    errors.price = t('form.priceInvalid');
  }
  if (price && !/^[A-Za-z]{3}$/.test(form.currency.trim())) {
    errors.currency = t('form.currencyInvalid');
  }
  for (const field of ['manageUrl', 'website'] as const) {
    if (form[field].trim() && !/^https?:\/\/\S+$/i.test(form[field].trim())) {
      errors[field] = t('form.urlInvalid');
    }
  }
  for (const field of ['guests', 'rooms'] as const) {
    if (form[field].trim() && !/^([1-9]|[1-9]\d)$/.test(form[field].trim())) {
      errors[field] = t('form.numberInvalid');
    }
  }
  return errors;
}

/** Message for the first problem of the form (current language), null if it can be saved. */
export function validateForm(form: AccommodationForm): string | null {
  return Object.values(formErrors(form))[0] ?? null;
}

/** Request body, call only after validateForm returned null. */
export function toInput(form: AccommodationForm, source: BookingSource): AccommodationBookingInput {
  const price = blank(form.price.replace(',', '.'));
  const { checkIn, checkOut } = stayTimes(form);
  return {
    source,
    name: form.name.trim(),
    addressLine: blank(form.addressLine),
    countryCode: blank(form.countryCode)?.toUpperCase() ?? null,
    latitude: form.latitude,
    longitude: form.longitude,
    placeRef: form.placeRef,
    phone: blank(form.phone),
    email: blank(form.email),
    website: blank(form.website),
    provider: blank(form.provider),
    reference: blank(form.reference),
    manageUrl: blank(form.manageUrl),
    checkIn: `${form.checkInDate}T${checkIn}:00`,
    checkOut: `${form.checkOutDate}T${checkOut}:00`,
    fallbackTimeZone: systemTimeZone(),
    checkInFrom: time(form.checkInFrom),
    checkInUntil: time(form.checkInUntil),
    checkOutUntil: time(form.checkOutUntil),
    guests: form.guests.trim() ? Number(form.guests) : null,
    rooms: form.rooms.trim() ? Number(form.rooms) : null,
    price: price ? Number(price).toFixed(2) : null,
    currency: price ? form.currency.trim().toUpperCase() : null,
    cancellationDeadline: form.cancellationDeadline ? `${form.cancellationDeadline.slice(0, 16)}:00` : null,
    notes: blank(form.notes),
  };
}

/** Arrival and departure (datetime-local) for a stop from the stay; same wall clock, the stop uses the system zone. */
export function stopTimesFromForm(form: AccommodationForm): { arrival: string; departure: string } {
  const { checkIn, checkOut } = stayTimes(form);
  return {
    arrival: form.checkInDate ? `${form.checkInDate}T${checkIn}` : '',
    departure: form.checkOutDate ? `${form.checkOutDate}T${checkOut}` : '',
  };
}
