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

export interface Route {
  id: number;
  fromStopId: number;
  toStopId: number;
  plannedAt: string | null;
}

export interface TripDetail {
  id: number;
  name: string;
  startAt: string;
  endAt: string;
  phase: TripPhase;
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
}

export interface NewStop {
  name: string;
  latitude: number | null;
  longitude: number | null;
  arrivalAt: string | null;
  departureAt: string | null;
}
