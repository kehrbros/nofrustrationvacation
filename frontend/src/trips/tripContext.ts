import { useOutletContext } from 'react-router';

import type { Booking } from '../bookings/model';
import type { LinePoint, Route, RouteLine, Stop, TripDetail, TripIncident } from './model';

/** What the trip page hands to its dialogs (incident, route, new stop, bookings). */
export interface TripContext {
  trip: TripDetail;
  /** Current incidents of the trip, without resolved ones. */
  incidents: TripIncident[];
  incidentsByRoute: Map<number, TripIncident[]>;
  /** Incidents that have ended, shown on the map with a green check until removed. */
  resolvedIncidents: TripIncident[];
  stopById: Map<number, Stop>;
  /** Bookings at the stops of the trip, ascending by check-in. */
  bookings: Booking[];
  bookingsByStop: Map<number, Booking[]>;
  /** Planned ways of the routes by route id, missing for routes not planned (yet). */
  routeLines: Map<number, RouteLine>;
  /** Shows the suggestions of a route on the map (null: none), see RouteSheet. */
  setRoutePreview: (preview: RoutePreview | null) => void;
  /**
   * Set: the next tap on the map picks a point and calls it with the metres per screen pixel there (routes and chips
   * do not open); null ends picking.
   */
  setPointPicker: (onPick: ((point: LinePoint, metersPerPixel: number) => void) | null) => void;
  /** Point marked on the map with a pink pin, e.g. the place of a test incident; null: none. */
  setPickedPoint: (point: LinePoint | null) => void;
  /** Loads trip and incidents again, e.g. after adding a stop. */
  reload: () => void;
  /** Back to the map. */
  close: () => void;
}

/** Ways to choose from for one route, drawn on the map; tapping a line selects it. */
export interface RoutePreview {
  routeId: number;
  lines: LinePoint[][];
  selected: number;
  onSelect: (index: number) => void;
}

export function useTripContext() {
  return useOutletContext<TripContext>();
}

export function routeName(route: Route, stopById: Map<number, Stop>): string {
  return `${stopById.get(route.fromStopId)?.name ?? '?'} → ${stopById.get(route.toStopId)?.name ?? '?'}`;
}
