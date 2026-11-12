import { t } from '../i18n';
import { isAppBooked } from '../bookings/appBookings';
import type { Booking } from '../bookings/model';
import type { TripDetail } from './model';

/** What deleting the stop also does, for the confirmation: bookings deleted with it, routes dropped or replanned. */
export function stopDeletionText(trip: TripDetail, stopId: number, bookings: Booking[]): string {
  const index = trip.stops.findIndex((stop) => stop.id === stopId);
  const previous = index > 0 ? trip.stops[index - 1] : undefined;
  const next = index >= 0 ? trip.stops[index + 1] : undefined;
  const parts: string[] = [];
  if (bookings.length === 1) {
    parts.push(t('deletion.stopBooking', { title: bookings[0].title }));
  } else if (bookings.length > 1) {
    parts.push(t('deletion.stopBookings', { count: bookings.length }));
  }
  if (previous && next) {
    parts.push(t('deletion.replanned', { from: previous.name, to: next.name }));
  } else if (previous) {
    parts.push(t('deletion.routeFromDropped', { name: previous.name }));
  } else if (next) {
    parts.push(t('deletion.routeToDropped', { name: next.name }));
  }
  return parts.join(' ') || t('deletion.stopOnly');
}

/** What deleting the trip also deletes, for the confirmation; warns about bookings made in the app. */
export function tripDeletionText(trip: TripDetail, bookings: Booking[]): string {
  const items = [
    trip.stops.length > 0 && t('common.stops', { count: trip.stops.length }),
    bookings.length > 0 && t('common.bookings', { count: bookings.length }),
  ].filter(Boolean);
  const text = items.length
    ? t('deletion.tripCascade', { items: items.join(t('common.and')) })
    : t('deletion.tripOnly');
  // booked and paid in the app: deleted as well, but not cancelled (cleaning up, Docs/domain.md#bookings)
  const inApp = bookings.filter(isAppBooked).length;
  return inApp > 0 ? `${text} ${t('deletion.tripAppBookings', { count: inApp })}` : text;
}
