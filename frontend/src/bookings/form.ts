import { shortTime, systemTimeZone, wallClock } from './localTime';
import type { AccommodationBookingInput, AccommodationDraft, Booking, BookingSource } from './model';

/** State of the accommodation form: what inputs hold (strings), times local at the accommodation. */
export interface AccommodationForm {
  name: string;
  addressLine: string;
  countryCode: string;
  latitude: number | null;
  longitude: number | null;
  placeRef: string | null;
  /** Shown next to "auf der Karte", e.g. the address found by the place search. */
  placeDetail: string;
  phone: string;
  email: string;
  website: string;
  provider: string;
  reference: string;
  manageUrl: string;
  checkInDate: string;
  checkInTime: string;
  checkOutDate: string;
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

  if (draft.checkInTime) {
    set('checkInTime', shortTime(draft.checkInTime));
  } else {
    form.checkInTime = shortTime(draft.checkInFrom) || DEFAULT_CHECK_IN;
    uncertain.add('checkInTime');
  }
  if (draft.checkOutTime) {
    set('checkOutTime', shortTime(draft.checkOutTime));
  } else {
    form.checkOutTime = shortTime(draft.checkOutUntil) || DEFAULT_CHECK_OUT;
    uncertain.add('checkOutTime');
  }
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

const blank = (value: string) => (value.trim() ? value.trim() : null);
const time = (value: string) => (value ? `${value.slice(0, 5)}:00` : null);

/** German message for the first problem of the form, null if it can be saved. */
export function validateForm(form: AccommodationForm): string | null {
  if (!form.name.trim()) {
    return 'Bitte den Namen der Unterkunft angeben.';
  }
  if (!form.checkInDate || !form.checkOutDate || !form.checkInTime || !form.checkOutTime) {
    return 'Bitte An- und Abreise mit Uhrzeit angeben.';
  }
  if (`${form.checkOutDate}T${form.checkOutTime}` <= `${form.checkInDate}T${form.checkInTime}`) {
    return 'Die Abreise muss nach der Anreise liegen.';
  }
  const price = form.price.trim().replace(',', '.');
  if (price && !/^\d{1,10}(\.\d{1,2})?$/.test(price)) {
    return 'Bitte den Preis als Zahl angeben, z. B. 412,50.';
  }
  if (price && !/^[A-Za-z]{3}$/.test(form.currency.trim())) {
    return 'Bitte die Währung als Code angeben, z. B. EUR.';
  }
  for (const [value, label] of [[form.manageUrl, 'Link zur Buchung'], [form.website, 'Website']] as const) {
    if (value.trim() && !/^https?:\/\/\S+$/i.test(value.trim())) {
      return `${label} muss mit https:// beginnen.`;
    }
  }
  for (const [value, label] of [[form.guests, 'Gäste'], [form.rooms, 'Zimmer']] as const) {
    if (value.trim() && !/^([1-9]|[1-9]\d)$/.test(value.trim())) {
      return `${label}: bitte eine Zahl von 1 bis 99.`;
    }
  }
  return null;
}

/** Request body, call only after validateForm returned null. */
export function toInput(form: AccommodationForm, source: BookingSource): AccommodationBookingInput {
  const price = blank(form.price.replace(',', '.'));
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
    checkIn: `${form.checkInDate}T${form.checkInTime.slice(0, 5)}:00`,
    checkOut: `${form.checkOutDate}T${form.checkOutTime.slice(0, 5)}:00`,
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
  return {
    arrival: form.checkInDate ? `${form.checkInDate}T${form.checkInTime.slice(0, 5)}` : '',
    departure: form.checkOutDate ? `${form.checkOutDate}T${form.checkOutTime.slice(0, 5)}` : '',
  };
}
