import { useOutletContext } from 'react-router';

import type { Booking } from '../bookings/model';
import type { Route, Stop, TripDetail, TripIncident } from './model';

/** What the trip page hands to its dialogs (incident, route, new stop, bookings). */
export interface TripContext {
  trip: TripDetail;
  incidents: TripIncident[];
  incidentsByRoute: Map<number, TripIncident[]>;
  stopById: Map<number, Stop>;
  /** Bookings at the stops of the trip, ascending by check-in. */
  bookings: Booking[];
  bookingsByStop: Map<number, Booking[]>;
  /** Loads trip and incidents again, e.g. after adding a stop. */
  reload: () => void;
  /** Back to the map. */
  close: () => void;
}

export function useTripContext() {
  return useOutletContext<TripContext>();
}

export function routeName(route: Route, stopById: Map<number, Stop>): string {
  return `${stopById.get(route.fromStopId)?.name ?? '?'} → ${stopById.get(route.toStopId)?.name ?? '?'}`;
}
