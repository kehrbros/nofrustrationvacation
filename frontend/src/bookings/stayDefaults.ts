import type { Stop, TripDetail } from '../trips/model';
import { toDayInput } from '../trips/time';
import type { StayDefaults } from './form';
import type { Booking } from './model';

/**
 * What a new booking at the stop starts with: arrival and departure day of the stop, the travelers of the trip as
 * guests (for trips without that number the guests of the latest booking of the trip).
 */
export function stayDefaults(trip: TripDetail, stop: Pick<Stop, 'arrivalAt' | 'departureAt'> | undefined, bookings: Booking[]): StayDefaults {
  const latestGuests = [...bookings].reverse().find((booking) => booking.accommodation?.guests)?.accommodation?.guests ?? null;
  return {
    checkInDate: stop?.arrivalAt ? toDayInput(stop.arrivalAt) : null,
    checkOutDate: stop?.departureAt ? toDayInput(stop.departureAt) : null,
    guests: trip.travelers ?? latestGuests,
  };
}

/** Point of the stop for the name suggestions, null without coordinates. */
export function stopPoint(stop: Pick<Stop, 'latitude' | 'longitude'> | undefined | null): { latitude: number; longitude: number } | null {
  return stop?.latitude != null && stop.longitude != null ? { latitude: stop.latitude, longitude: stop.longitude } : null;
}
