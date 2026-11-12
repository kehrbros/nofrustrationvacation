import type { Booking } from './model';

/** Bookings made and paid in the app (LiteAPI, PayPal), which the app can cancel, see Docs/accommodation-alternatives.md. */

/** Booked in the app and not cancelled: removing it from the trip asks whether to cancel it as well. */
export function isAppBooked(booking: Booking): boolean {
  return booking.source === 'SEARCH' && booking.status !== 'CANCELLED';
}

/** Booked in the app and still free to cancel: the app cancels at the hotel, PayPal refunds. */
export function isFreeToCancel(booking: Booking, now: number = Date.now()): boolean {
  return isAppBooked(booking) && booking.cancellationDeadline != null
    && new Date(booking.cancellationDeadline).getTime() > now;
}
