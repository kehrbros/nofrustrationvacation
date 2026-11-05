import type { Booking } from '../bookings/model';
import type { TripDetail } from './model';

/** What deleting the stop also does, for the confirmation: bookings deleted with it, routes dropped or replanned. */
export function stopDeletionText(trip: TripDetail, stopId: number, bookings: Booking[]): string {
  const index = trip.stops.findIndex((stop) => stop.id === stopId);
  const previous = index > 0 ? trip.stops[index - 1] : undefined;
  const next = index >= 0 ? trip.stops[index + 1] : undefined;
  const parts: string[] = [];
  if (bookings.length === 1) {
    parts.push(`Die Buchung „${bookings[0].title}“ wird mitgelöscht.`);
  } else if (bookings.length > 1) {
    parts.push(`Die ${bookings.length} Buchungen am Stop werden mitgelöscht.`);
  }
  if (previous && next) {
    parts.push(`Zwischen ${previous.name} und ${next.name} wird eine neue Route geplant.`);
  } else if (previous ?? next) {
    parts.push(`Die Route ${previous ? `von ${previous.name}` : `nach ${next!.name}`} entfällt.`);
  }
  return parts.join(' ') || 'Der Stop wird aus der Reise entfernt.';
}

/** What deleting the trip also deletes, for the confirmation. */
export function tripDeletionText(trip: TripDetail, bookings: Booking[]): string {
  const items = [
    trip.stops.length === 1 ? '1 Stop' : trip.stops.length > 1 && `${trip.stops.length} Stops`,
    bookings.length === 1 ? '1 Buchung' : bookings.length > 1 && `${bookings.length} Buchungen`,
  ].filter(Boolean);
  return items.length
    ? `${items.join(' und ')} mit allen Routen werden mitgelöscht.`
    : 'Die Reise wird gelöscht.';
}
