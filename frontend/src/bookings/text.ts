import { formatLocalDate, formatStay, nights } from './localTime';
import type { Booking } from './model';

/** "12.–15. Juli · 3 Nächte" */
export function stayLabel(booking: Booking): string {
  if (!booking.startsAt || !booking.endsAt) {
    return 'ohne Zeitraum';
  }
  const count = nights(booking.startsAt, booking.endsAt);
  return `${formatStay(booking.startsAt, booking.endsAt)} · ${count === 1 ? '1 Nacht' : `${count} Nächte`}`;
}

/** Day of arrival; the stay has no time of its own, the house rules say when check-in is possible. */
export function checkInLabel(booking: Booking): string | null {
  return booking.startsAt ? formatLocalDate(booking.startsAt) : null;
}

export function checkOutLabel(booking: Booking): string | null {
  return booking.endsAt ? formatLocalDate(booking.endsAt) : null;
}

/** "412,50 €" */
export function priceLabel(booking: Booking): string | null {
  if (!booking.price || !booking.currency) {
    return null;
  }
  try {
    return new Intl.NumberFormat('de-DE', { style: 'currency', currency: booking.currency }).format(Number(booking.price));
  } catch {
    return `${booking.price} ${booking.currency}`;
  }
}

/** Link to the route in the maps app of the phone. */
export function directionsUrl(latitude: number, longitude: number): string {
  return `https://www.google.com/maps/dir/?api=1&destination=${latitude},${longitude}`;
}
