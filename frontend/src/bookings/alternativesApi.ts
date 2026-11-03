import {
  abortReservation as abortReservationRequest,
  alternativeCriteria,
  alternativesStatus,
  bookReservation as bookReservationRequest,
  cancelBooking as cancelBookingRequest,
  getReservation,
  reserveAlternative as reserveAlternativeRequest,
  searchAlternatives as searchAlternativesRequest,
} from '../generated/api';
import type { AlternativeCandidateDto, ReservationDto } from '../generated/api';
import { TripsApiError } from '../trips/tripsApi';

/** Search and booking of an alternative accommodation (LiteAPI, paid with PayPal), see Docs/accommodation-alternatives.md. */

export interface AlternativesStatus {
  searchAvailable: boolean;
  bookingAvailable: boolean;
  rankingAvailable: boolean;
}

/** Dates yyyy-mm-dd local at the place, maxPrice the total for the stay as decimal string. */
export interface Criteria {
  latitude: number | null;
  longitude: number | null;
  placeName: string | null;
  radiusMeters: number;
  checkIn: string;
  checkOut: string;
  guests: number | null;
  rooms: number;
  maxPrice: string;
  currency: string;
  excludeHotelId: string | null;
  missing: string[];
}

export interface Candidate {
  hotelId: string;
  name: string;
  address: string | null;
  latitude: number;
  longitude: number;
  distanceMeters: number;
  stars: number | null;
  rating: number | null;
  photoUrl: string | null;
  offerId: string;
  roomName: string | null;
  boardName: string | null;
  refundable: boolean;
  /** Local at the hotel, yyyy-mm-ddTHH:MM:SS. */
  freeCancellationUntil: string | null;
  price: string;
  currency: string;
  possiblySame: boolean;
  reason: string | null;
}

export interface SearchResult {
  candidates: Candidate[];
  recommendedHotelId: string | null;
  ranked: boolean;
  offersChecked: number;
}

export type ReservationStatus = 'PREBOOKED' | 'PAYMENT_PENDING' | 'AUTHORIZED' | 'PROVIDER_BOOKED' | 'BOOKED' | 'FAILED';

export interface Reservation {
  id: number;
  tripId: number;
  replacesBookingId: number | null;
  bookingId: number | null;
  status: ReservationStatus;
  hotelName: string;
  hotelAddress: string | null;
  checkIn: string;
  checkOut: string;
  guests: number;
  rooms: number;
  price: string;
  fee: string;
  currency: string;
  refundable: boolean;
  freeCancellationUntil: string | null;
  approveUrl: string | null;
  failure: string | null;
}

export interface Holder {
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
}

async function unwrap<T>(call: Promise<{ data?: T; error?: unknown; response?: Response }>): Promise<T> {
  const { data, error, response } = await call;
  if (error !== undefined || !response?.ok) {
    const code = typeof error === 'object' && error && 'code' in error ? String(error.code) : undefined;
    throw new TripsApiError(response?.status, code);
  }
  return data as T;
}

function toCandidate(dto: AlternativeCandidateDto): Candidate {
  return {
    hotelId: dto.hotelId ?? '',
    name: dto.name ?? '',
    address: dto.address ?? null,
    latitude: dto.latitude ?? 0,
    longitude: dto.longitude ?? 0,
    distanceMeters: dto.distanceMeters ?? 0,
    stars: dto.stars ?? null,
    rating: dto.rating ?? null,
    photoUrl: dto.photoUrl ?? null,
    offerId: dto.offerId ?? '',
    roomName: dto.roomName ?? null,
    boardName: dto.boardName ?? null,
    refundable: dto.refundable ?? false,
    freeCancellationUntil: dto.freeCancellationUntil ?? null,
    price: dto.price ?? '0',
    currency: dto.currency ?? 'EUR',
    possiblySame: dto.possiblySame ?? false,
    reason: dto.reason ?? null,
  };
}

function toReservation(dto: ReservationDto): Reservation {
  return {
    id: dto.id!,
    tripId: dto.tripId!,
    replacesBookingId: dto.replacesBookingId ?? null,
    bookingId: dto.bookingId ?? null,
    status: dto.status ?? 'FAILED',
    hotelName: dto.hotelName ?? '',
    hotelAddress: dto.hotelAddress ?? null,
    checkIn: dto.checkIn ?? '',
    checkOut: dto.checkOut ?? '',
    guests: dto.guests ?? 1,
    rooms: dto.rooms ?? 1,
    price: dto.price ?? '0',
    fee: dto.fee ?? '0',
    currency: dto.currency ?? 'EUR',
    refundable: dto.refundable ?? false,
    freeCancellationUntil: dto.freeCancellationUntil ?? null,
    approveUrl: dto.approveUrl ?? null,
    failure: dto.failure ?? null,
  };
}

/** Everything unavailable if the check fails. */
export async function fetchAlternativesStatus(): Promise<AlternativesStatus> {
  try {
    const status = await unwrap(alternativesStatus());
    return {
      searchAvailable: status.searchAvailable ?? false,
      bookingAvailable: status.bookingAvailable ?? false,
      rankingAvailable: status.rankingAvailable ?? false,
    };
  } catch {
    return { searchAvailable: false, bookingAvailable: false, rankingAvailable: false };
  }
}

export async function fetchCriteria(bookingId: number): Promise<Criteria> {
  const dto = await unwrap(alternativeCriteria({ path: { bookingId } }));
  return {
    latitude: dto.latitude ?? null,
    longitude: dto.longitude ?? null,
    placeName: dto.placeName ?? null,
    radiusMeters: dto.radiusMeters ?? 5000,
    checkIn: dto.checkIn ?? '',
    checkOut: dto.checkOut ?? '',
    guests: dto.guests ?? null,
    rooms: dto.rooms ?? 1,
    maxPrice: dto.maxPrice ?? '',
    currency: dto.currency ?? 'EUR',
    excludeHotelId: dto.excludeHotelId ?? null,
    missing: dto.missing ?? [],
  };
}

function criteriaBody(criteria: Criteria) {
  return {
    latitude: criteria.latitude ?? undefined,
    longitude: criteria.longitude ?? undefined,
    radiusMeters: criteria.radiusMeters,
    checkIn: criteria.checkIn,
    checkOut: criteria.checkOut,
    guests: criteria.guests ?? undefined,
    rooms: criteria.rooms,
    maxPrice: criteria.maxPrice.trim() ? criteria.maxPrice.trim().replace(',', '.') : undefined,
    currency: criteria.currency,
    excludeHotelId: criteria.excludeHotelId ?? undefined,
  };
}

/** Offers that meet all criteria, best first. Takes some seconds. */
export async function searchAlternatives(bookingId: number, criteria: Criteria): Promise<SearchResult> {
  const dto = await unwrap(searchAlternativesRequest({ path: { bookingId }, body: criteriaBody(criteria) }));
  return {
    candidates: (dto.candidates ?? []).map(toCandidate),
    recommendedHotelId: dto.recommendedHotelId ?? null,
    ranked: dto.ranked ?? false,
    offersChecked: dto.offersChecked ?? 0,
  };
}

/** Locks the price and creates the PayPal payment; the user approves it at approveUrl. */
export async function reserveAlternative(bookingId: number, criteria: Criteria, candidate: Candidate,
  holder: Holder): Promise<Reservation> {
  const { checkIn, checkOut, guests, rooms, maxPrice, currency } = criteriaBody(criteria);
  return toReservation(await unwrap(reserveAlternativeRequest({
    path: { bookingId },
    body: { hotelId: candidate.hotelId, offerId: candidate.offerId, checkIn, checkOut, guests, rooms, maxPrice, currency, ...holder },
  })));
}

export async function fetchReservation(reservationId: number): Promise<Reservation> {
  return toReservation(await unwrap(getReservation({ path: { reservationId } })));
}

/** After the approval at PayPal: pay, book, add the booking to the stop. Safe to repeat. */
export async function bookReservation(reservationId: number): Promise<Reservation> {
  return toReservation(await unwrap(bookReservationRequest({ path: { reservationId } })));
}

export async function abortReservation(reservationId: number): Promise<Reservation> {
  return toReservation(await unwrap(abortReservationRequest({ path: { reservationId } })));
}

/** Marks the booking as cancelled in the app, the provider is not contacted. */
export async function markBookingCancelled(bookingId: number): Promise<void> {
  await unwrap(cancelBookingRequest({ path: { bookingId } }));
}

