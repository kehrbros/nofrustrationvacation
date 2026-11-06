/** Types the pages work with, mapped from the generated API types (where every field is optional). */

export type TripPhase = 'UPCOMING' | 'CURRENT' | 'PAST';
export type IncidentType = 'CLOSURE' | 'CONGESTION' | 'ROADWORKS' | 'ACCIDENT' | 'OTHER';
export type Impact = 'BLOCKED' | 'DELAYED';
/** Effect of a consequence on the arrival at the target stop, see Docs/consequence-assessment.md. */
export type Category = 'TOO_LATE' | 'DELAYED' | 'NO_IMPACT';
export type MatchKind = 'DIRECT' | 'BY_SHIFT' | 'MARGINAL';

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
  /** null: not assessed yet. */
  category: Category | null;
  match: MatchKind;
  /** Own delay on this route in minutes, null: not passable. */
  assessedDelayMinutes: number | null;
  /** assessedDelayMinutes is an assumption of the source, show as estimated. */
  delayAssumed: boolean;
  /** Arrival at the target stop after this incident, null if not passable or not planned. */
  expectedArrival: string | null;
  /** Arrival of the target stop, null without. */
  deadline: string | null;
  timesUnconfirmed: boolean;
  /** Stretch of the route the incident covers, empty if unknown. */
  geometry: LinePoint[];
}

/** Situation of one route with its incidents, against the arrival at its target stop. */
export interface RouteIncidentSummary {
  routeId: number;
  worstCategory: Category | null;
  plannedArrival: string | null;
  expectedArrival: string | null;
  deadline: string | null;
  /** Deadline minus expected arrival in minutes, negative: too late. */
  slackMinutes: number | null;
  unpassable: boolean;
  plannedTooLate: boolean;
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

export type OptionLabel =
  | 'FASTEST'
  | 'SHORTEST'
  | 'CURRENT'
  | 'AVOIDS_INCIDENT'
  | 'HAS_INCIDENT'
  | 'LATER_DEPARTURE'
  | 'EARLIER_DEPARTURE'
  | 'FERRY';

export interface LinePoint {
  latitude: number;
  longitude: number;
}

/** Point of a suggestion that becomes a waypoint when it is chosen; waypointId keeps a waypoint of the user. */
export interface ChoicePoint {
  latitude: number;
  longitude: number;
  roadHint: string | null;
  waypointId: number | null;
}

export interface RouteConflict {
  incidentId: number;
  impact: Impact;
  delaySeconds: number | null;
}

/** One way between two stops, computed per request and not stored; choosing it stores its waypoints. */
export interface RouteOption {
  rank: number;
  labels: OptionLabel[];
  /** Main road of the part that differs from the fastest way, e.g. "SH 2". */
  via: string | null;
  departureAt: string;
  arrivalAt: string;
  distanceMeters: number;
  durationSeconds: number;
  delaySeconds: number;
  geometry: LinePoint[];
  waypoints: ChoicePoint[];
  conflicts: RouteConflict[];
}

export type SuggestionProblem =
  | 'MISSING_COORDINATES'
  | 'MISSING_DEPARTURE'
  | 'NO_ROUTE'
  | 'WAYPOINT_UNREACHABLE'
  | 'CONSEQUENCE_PASSED';

export interface RouteSuggestions {
  options: RouteOption[];
  problem: SuggestionProblem | null;
}

/** Planned way of a route for the map. */
export interface RouteLine {
  routeId: number;
  geometry: LinePoint[];
  distanceMeters: number;
  durationSeconds: number;
  via: string | null;
}
