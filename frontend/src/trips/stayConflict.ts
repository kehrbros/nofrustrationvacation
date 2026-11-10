import { t } from '../i18n';
import { formatStay } from '../bookings/localTime';
import { formatDay } from './time';
import { TripsApiError } from './tripsApi';

/**
 * Bookings must lie within the days of their stop (Docs/domain.md#bookings). The backend answers a change
 * that breaks this with 409 and what the user needs to decide; these types read it from the problem details.
 */

export interface OutsideBooking {
  bookingId: number;
  title: string;
  startsAt: string | null;
  endsAt: string | null;
  /** False if booked in the app or if no night would be left. */
  trimmable: boolean;
}

/** Changing the stop: bookings at it lie outside the new days. */
export interface StopBookingsConflict {
  bookings: OutsideBooking[];
  /** Arrival moved to the earliest check-in day, null if that side fits. */
  extendedArrivalAt: string | null;
  extendedDepartureAt: string | null;
}

/** Changing a booking: it lies outside the days of its stop. */
export interface BookingOutsideStop {
  stopArrivalAt: string | null;
  stopDepartureAt: string | null;
  extendedArrivalAt: string | null;
  extendedDepartureAt: string | null;
  trimmable: boolean;
}

const text = (value: unknown): string | null => (typeof value === 'string' ? value : null);

function problem(failure: unknown, code: string): Record<string, unknown> | null {
  return failure instanceof TripsApiError && failure.code === code && failure.body ? failure.body : null;
}

export function stopBookingsConflict(failure: unknown): StopBookingsConflict | null {
  const body = problem(failure, 'STOP_BOOKINGS_CONFLICT');
  if (!body) {
    return null;
  }
  const bookings = Array.isArray(body.bookings) ? (body.bookings as Record<string, unknown>[]) : [];
  return {
    bookings: bookings.map((booking) => ({
      bookingId: Number(booking.bookingId),
      title: text(booking.title) ?? '',
      startsAt: text(booking.startsAt),
      endsAt: text(booking.endsAt),
      trimmable: booking.trimmable === true,
    })),
    extendedArrivalAt: text(body.extendedArrivalAt),
    extendedDepartureAt: text(body.extendedDepartureAt),
  };
}

export function bookingOutsideStop(failure: unknown): BookingOutsideStop | null {
  const body = problem(failure, 'BOOKING_OUTSIDE_STOP');
  if (!body) {
    return null;
  }
  return {
    stopArrivalAt: text(body.stopArrivalAt),
    stopDepartureAt: text(body.stopDepartureAt),
    extendedArrivalAt: text(body.extendedArrivalAt),
    extendedDepartureAt: text(body.extendedDepartureAt),
    trimmable: body.trimmable === true,
  };
}

/** "Mo., 12. Juli – Do., 15. Juli", "ab Mo., 12. Juli" or "bis …" for the days of a stop (system time zone). */
export function stopDaysLabel(arrivalAt: string | null, departureAt: string | null): string {
  if (arrivalAt && departureAt) {
    return `${formatDay(arrivalAt)} – ${formatDay(departureAt)}`;
  }
  if (arrivalAt) {
    return t('stayConflict.from', { day: formatDay(arrivalAt) });
  }
  return departureAt ? t('stayConflict.until', { day: formatDay(departureAt) }) : '';
}

/** "Hotel Waterloo · 12.–15. Juli" (days at the place). */
export function outsideBookingLabel(booking: OutsideBooking): string {
  return booking.startsAt && booking.endsAt ? `${booking.title} · ${formatStay(booking.startsAt, booking.endsAt)}` : booking.title;
}
