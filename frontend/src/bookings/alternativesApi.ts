import {
  abortReservation as abortReservationRequest,
  alternativeCriteria,
  alternativesStatus,
  bookReservation as bookReservationRequest,
  cancelBooking as cancelBookingRequest,
  cancelWithRefund as cancelWithRefundRequest,
  checkReservationRoute,
  getReservation,
  reserveAlternative as reserveAlternativeRequest,
  reserveAtStop as reserveAtStopRequest,
  searchAlternatives as searchAlternativesRequest,
  unfinishedReservations as unfinishedReservationsRequest,
} from '../generated/api';
import type { AlternativeCandidateDto, ReservationDto } from '../generated/api';
import { TripsApiError } from '../trips/tripsApi';

/** Search and booking of an alternative accommodation (LiteAPI, paid with PayPal), see Docs/accommodation-alternatives.md. */

export interface AlternativesStatus {
  searchAvailable: boolean;
  bookingAvailable: boolean;
  rankingAvailable: boolean;
  /** Public client id for the PayPal button, null without PayPal. */
  payPalClientId: string | null;
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
  /** At least these stars (1–5), hotels without stars are left out; null: no limit. */
  minStars: number | null;
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
  /** Hotels meeting all criteria that the assistant left out as not comparable to the current accommodation. */
  excludedByAssistant: number;
}

export type ReservationStatus = 'PREBOOKED' | 'PAYMENT_PENDING' | 'AUTHORIZED' | 'PROVIDER_BOOKED' | 'BOOKED' | 'FAILED'
  | 'NEEDS_ATTENTION' | 'CANCELLED';

export interface Reservation {
  id: number;
  tripId: number;
  stopId: number;
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
  payPalOrderId: string | null;
  failure: string | null;
  refundAmount: string | null;
  refunded: boolean;
  /** The booking created once booked, with the accommodation's coordinates and the booking number. */
  booking: { id: number; reference: string | null; latitude: number | null; longitude: number | null } | null;
  /**
   * Routes planned again because the stop moved to the place of the new hotel (more than 10 km away), only in the
   * answer that booked it.
   */
  replannedRouteIds: number[];
}

export type RouteCheckProblem = 'FIRST_STOP' | 'UNDER_WAY' | 'MISSING_COORDINATES' | 'MISSING_DEPARTURE' | 'NO_ROUTE'
  | 'ROUTING_UNAVAILABLE';

/**
 * Way to the hotel of a reservation before paying. Only with otherPlace the stop moves there after booking and the
 * other values are set.
 */
export interface RouteCheck {
  otherPlace: boolean;
  distanceMeters: number;
  routeId: number | null;
  /** ISO with offset, arrival on the best way including delays. */
  arrivalAt: string | null;
  checkInUntil: string | null;
  late: boolean;
  blocked: boolean;
  delaySeconds: number;
  problem: RouteCheckProblem | null;
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
    throw new TripsApiError(response?.status, code, typeof error === 'object' && error ? (error as Record<string, unknown>) : undefined);
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
    stopId: dto.stopId!,
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
    payPalOrderId: dto.payPalOrderId ?? null,
    failure: dto.failure ?? null,
    refundAmount: dto.refundAmount ?? null,
    refunded: dto.refunded ?? false,
    booking: dto.booking?.id != null ? {
      id: dto.booking.id,
      reference: dto.booking.reference ?? null,
      latitude: dto.booking.accommodation?.latitude ?? null,
      longitude: dto.booking.accommodation?.longitude ?? null,
    } : null,
    replannedRouteIds: dto.replannedRouteIds ?? [],
  };
}

/** Way to the hotel before paying; null if it cannot be checked (the hint is left out then). */
export async function fetchRouteCheck(reservationId: number): Promise<RouteCheck | null> {
  try {
    const dto = await unwrap(checkReservationRoute({ path: { reservationId } }));
    return {
      otherPlace: dto.otherPlace ?? false,
      distanceMeters: dto.distanceMeters ?? 0,
      routeId: dto.routeId ?? null,
      arrivalAt: dto.arrivalAt ?? null,
      checkInUntil: dto.checkInUntil ?? null,
      late: dto.late ?? false,
      blocked: dto.blocked ?? false,
      delaySeconds: dto.delaySeconds ?? 0,
      problem: (dto.problem ?? null) as RouteCheckProblem | null,
    };
  } catch {
    return null;
  }
}

/** Everything unavailable if the check fails. */
export async function fetchAlternativesStatus(): Promise<AlternativesStatus> {
  try {
    const status = await unwrap(alternativesStatus());
    return {
      searchAvailable: status.searchAvailable ?? false,
      bookingAvailable: status.bookingAvailable ?? false,
      rankingAvailable: status.rankingAvailable ?? false,
      payPalClientId: status.payPalClientId ?? null,
    };
  } catch {
    return { searchAvailable: false, bookingAvailable: false, rankingAvailable: false, payPalClientId: null };
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
    minStars: dto.minStars ?? null,
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
    minStars: criteria.minStars ?? undefined,
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
    excludedByAssistant: dto.excludedByAssistant ?? 0,
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

/** An offer as a proposal holds it: hotel, offer, dates, guests and the total price, see Docs/advisor.md. */
export interface StayOffer {
  hotelId: string;
  offerId: string;
  checkIn: string;
  checkOut: string;
  guests: number;
  rooms: number;
  price: string;
  currency: string;
}

/**
 * Locks the price of an offer taken from a proposal and creates the PayPal payment: as an alternative to the booking
 * it replaces, or at the stop (a night on the way). The price of the proposal is the maximum.
 */
export async function reserveStay(stopId: number, replacesBookingId: number | null, offer: StayOffer,
  holder: Holder): Promise<Reservation> {
  const body = {
    hotelId: offer.hotelId, offerId: offer.offerId, checkIn: offer.checkIn, checkOut: offer.checkOut,
    guests: offer.guests, rooms: offer.rooms, maxPrice: offer.price, currency: offer.currency, ...holder,
  };
  return toReservation(await unwrap(replacesBookingId != null
    ? reserveAlternativeRequest({ path: { bookingId: replacesBookingId }, body })
    : reserveAtStopRequest({ path: { stopId }, body })));
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


/** Reservations of the trip whose payment or booking is not finished (or has to be checked). */
export async function fetchUnfinishedReservations(tripId: number): Promise<Reservation[]> {
  return (await unwrap(unfinishedReservationsRequest({ path: { tripId } }))).map(toReservation);
}

/** Cancels a booking made in the app at the hotel and refunds the PayPal payment. */
export async function cancelWithRefund(bookingId: number): Promise<Reservation> {
  return toReservation(await unwrap(cancelWithRefundRequest({ path: { bookingId } })));
}

const HOLDER_KEY = 'nfv.bookingHolder';

/** Guest data of the last booking on this device, so the next rebooking needs no form. */
export function rememberedHolder(): Holder | null {
  try {
    const value = JSON.parse(localStorage.getItem(HOLDER_KEY) ?? 'null') as Partial<Holder> | null;
    return value && typeof value.firstName === 'string' ? {
      firstName: value.firstName ?? '', lastName: value.lastName ?? '', email: value.email ?? '', phone: value.phone ?? '',
    } : null;
  } catch {
    return null;
  }
}

export function rememberHolder(holder: Holder): void {
  try {
    localStorage.setItem(HOLDER_KEY, JSON.stringify(holder));
  } catch {
    // private mode: asked again next time
  }
}
