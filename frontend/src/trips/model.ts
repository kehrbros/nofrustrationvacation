/** Types the pages work with, mapped from the generated API types (where every field is optional). */

export type TripPhase = 'UPCOMING' | 'CURRENT' | 'PAST';
export type IncidentType = 'CLOSURE' | 'CONGESTION' | 'ROADWORKS' | 'ACCIDENT' | 'OTHER';
export type Impact = 'BLOCKED' | 'DELAYED';

export interface TripSummary {
  id: number;
  name: string;
  /** ISO 8601 with the offset the trip was entered in. */
  startAt: string;
  endAt: string;
  phase: TripPhase;
  stopCount: number;
  nextStopName: string | null;
  hasCoverImage: boolean;
  /** Current incidents on the routes of the trip. */
  incidentCount: number;
}

export interface Stop {
  id: number;
  position: number;
  name: string;
  latitude: number | null;
  longitude: number | null;
  arrivalAt: string | null;
  departureAt: string | null;
}

export type PlanOutcome = 'PLANNED' | 'MISSING_COORDINATES' | 'MISSING_DEPARTURE' | 'NO_ROUTE' | 'ROUTING_UNAVAILABLE';

export interface Route {
  id: number;
  fromStopId: number;
  toStopId: number;
  plannedAt: string | null;
  /** Outcome of the last planning, null until planned or after a change of its stops or waypoints. */
  planOutcome: PlanOutcome | null;
  planReason: string | null;
}

export interface TripDetail {
  id: number;
  name: string;
  startAt: string;
  endAt: string;
  phase: TripPhase;
  /** Number of travelers, suggested as guests of bookings. */
  travelers: number | null;
  hasCoverImage: boolean;
  stops: Stop[];
  routes: Route[];
}

export interface TripIncident {
  incidentId: number;
  routeId: number;
  type: IncidentType;
  description: string | null;
  impact: Impact;
  delayMinutes: number | null;
  validFrom: string;
  validTo: string | null;
  detectedAt: string;
  updatedAt: string;
}

export interface NewTrip {
  name: string;
  startAt: string;
  endAt: string;
  travelers: number | null;
}

export interface NewStop {
  name: string;
  latitude: number | null;
  longitude: number | null;
  arrivalAt: string | null;
  departureAt: string | null;
}
