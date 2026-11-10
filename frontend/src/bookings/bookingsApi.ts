import {
  assistantStatus,
  changeAccommodationBooking as changeAccommodationBookingRequest,
  createAccommodationBooking as createAccommodationBookingRequest,
  deleteBooking as deleteBookingRequest,
  extractAccommodation as extractAccommodationRequest,
  listBookings,
  lookUpAccommodation as lookUpAccommodationRequest,
  suggestAccommodations as suggestAccommodationsRequest,
} from '../generated/api';
import type { AccommodationDraftDto, AccommodationDto, BookingDto } from '../generated/api';
import { TripsApiError } from '../trips/tripsApi';
import type {
  Accommodation,
  AccommodationBookingInput,
  AccommodationDraft,
  AccommodationFacts,
  AccommodationSuggestion,
  Booking,
} from './model';

async function unwrap<T>(call: Promise<{ data?: T; error?: unknown; response?: Response }>): Promise<T> {
  const { data, error, response } = await call;
  if (error !== undefined || !response?.ok) {
    const code = typeof error === 'object' && error && 'code' in error ? String(error.code) : undefined;
    throw new TripsApiError(response?.status, code, typeof error === 'object' && error ? (error as Record<string, unknown>) : undefined);
  }
  return data as T;
}

function toAccommodation(dto: AccommodationDto): Accommodation {
  return {
    name: dto.name ?? '',
    addressLine: dto.addressLine ?? null,
    countryCode: dto.countryCode ?? null,
    latitude: dto.latitude ?? null,
    longitude: dto.longitude ?? null,
    timeZone: dto.timeZone ?? 'UTC',
    phone: dto.phone ?? null,
    email: dto.email ?? null,
    website: dto.website ?? null,
    placeRef: dto.placeRef ?? null,
    checkInFrom: dto.checkInFrom ?? null,
    checkInUntil: dto.checkInUntil ?? null,
    checkOutUntil: dto.checkOutUntil ?? null,
    guests: dto.guests ?? null,
    rooms: dto.rooms ?? null,
  };
}

function toBooking(dto: BookingDto): Booking {
  return {
    id: dto.id!,
    stopId: dto.stopId!,
    type: dto.type ?? 'OTHER',
    status: dto.status ?? 'CONFIRMED',
    source: dto.source ?? 'MANUAL',
    title: dto.title ?? '',
    provider: dto.provider ?? null,
    reference: dto.reference ?? null,
    manageUrl: dto.manageUrl ?? null,
    startsAt: dto.startsAt ?? null,
    endsAt: dto.endsAt ?? null,
    price: dto.price ?? null,
    currency: dto.currency ?? null,
    cancellationDeadline: dto.cancellationDeadline ?? null,
    notes: dto.notes ?? null,
    accommodation: dto.accommodation ? toAccommodation(dto.accommodation) : null,
  };
}

function toDraft(dto: AccommodationDraftDto): AccommodationDraft {
  return {
    found: dto.found ?? false,
    name: dto.name ?? null,
    addressLine: dto.addressLine ?? null,
    countryCode: dto.countryCode ?? null,
    phone: dto.phone ?? null,
    email: dto.email ?? null,
    website: dto.website ?? null,
    provider: dto.provider ?? null,
    reference: dto.reference ?? null,
    manageUrl: dto.manageUrl ?? null,
    checkInDate: dto.checkInDate ?? null,
    checkInTime: dto.checkInTime ?? null,
    checkOutDate: dto.checkOutDate ?? null,
    checkOutTime: dto.checkOutTime ?? null,
    checkInFrom: dto.checkInFrom ?? null,
    checkInUntil: dto.checkInUntil ?? null,
    checkOutUntil: dto.checkOutUntil ?? null,
    guests: dto.guests ?? null,
    rooms: dto.rooms ?? null,
    price: dto.price ?? null,
    currency: dto.currency ?? null,
    cancellationDeadline: dto.cancellationDeadline ?? null,
    uncertainFields: dto.uncertainFields ?? [],
  };
}

/** Bookings at the stops of the trip, ascending by check-in. */
export async function fetchTripBookings(tripId: number): Promise<Booking[]> {
  return (await unwrap(listBookings({ path: { tripId } }))).map(toBooking);
}

export async function createAccommodationBooking(stopId: number, input: AccommodationBookingInput): Promise<Booking> {
  return toBooking(await unwrap(createAccommodationBookingRequest({ path: { stopId }, body: input })));
}

/**
 * Replaces the booking. Outside the days of its stop 409 BOOKING_OUTSIDE_STOP (see stayConflict.ts), unless
 * `stopConflict` extends the stop or shortens the booking.
 */
export async function changeAccommodationBooking(
  bookingId: number,
  input: AccommodationBookingInput,
  stopConflict?: 'EXTEND_STOP' | 'TRIM_BOOKING',
): Promise<Booking> {
  return toBooking(await unwrap(changeAccommodationBookingRequest({
    path: { bookingId },
    query: stopConflict ? { stopConflict } : undefined,
    body: input,
  })));
}

export async function deleteBooking(bookingId: number): Promise<void> {
  await unwrap(deleteBookingRequest({ path: { bookingId } }));
}

/** Whether confirmations can be read automatically; false if the check fails. */
export async function isExtractionAvailable(): Promise<boolean> {
  try {
    return (await unwrap(assistantStatus())).extractionAvailable ?? false;
  } catch {
    return false;
  }
}

/** Reads the booking from pasted text and/or a screenshot or PDF. Nothing is stored. */
export async function extractAccommodation(text: string | null, file: File | null): Promise<AccommodationDraft> {
  const body: { text?: string; file?: Blob } = {};
  if (text?.trim()) {
    body.text = text.trim();
  }
  if (file) {
    body.file = file;
  }
  return toDraft(await unwrap(extractAccommodationRequest({ body })));
}

/** LiteAPI accommodations whose name contains the query, near the point first; empty on any failure. */
export async function suggestAccommodations(
  query: string,
  near: { latitude: number; longitude: number } | null,
  signal?: AbortSignal,
): Promise<AccommodationSuggestion[]> {
  try {
    const found = await unwrap(
      suggestAccommodationsRequest({
        query: { query, latitude: near?.latitude, longitude: near?.longitude },
        signal,
      }),
    );
    return found.map((dto) => ({
      hotelId: dto.hotelId!,
      name: dto.name ?? '',
      addressLine: dto.addressLine ?? null,
      countryCode: dto.countryCode ?? null,
      latitude: dto.latitude ?? null,
      longitude: dto.longitude ?? null,
      stars: dto.stars ?? null,
    }));
  } catch {
    return [];
  }
}

/** Contact and house rules: LiteAPI hotel data, what is missing looked up on the web by the assistant. */
export async function lookUpAccommodation(query: {
  hotelId: string | null;
  name: string;
  addressLine: string | null;
  countryCode: string | null;
  latitude: number | null;
  longitude: number | null;
}): Promise<AccommodationFacts> {
  const dto = await unwrap(lookUpAccommodationRequest({ body: query }));
  return {
    addressLine: dto.addressLine ?? null,
    countryCode: dto.countryCode ?? null,
    phone: dto.phone ?? null,
    email: dto.email ?? null,
    website: dto.website ?? null,
    checkInFrom: dto.checkInFrom ?? null,
    checkInUntil: dto.checkInUntil ?? null,
    checkOutUntil: dto.checkOutUntil ?? null,
    fromLiteApi: dto.fromLiteApi ?? [],
    fromAssistant: dto.fromAssistant ?? [],
    researched: dto.researched ?? false,
    sources: dto.sources ?? [],
  };
}
