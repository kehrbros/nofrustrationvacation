import { choose, getRoute, getRouteLines, suggest } from '../generated/api';
import type { RouteOptionDto } from '../generated/api';
import type { ChoicePoint, PlanOutcome, RouteLine, RouteOption, RouteSuggestions } from './model';
import { TripsApiError } from './tripsApi';

/** Route suggestions and the planned ways, see Docs/recommendations.md#api. */

async function unwrap<T>(call: Promise<{ data?: T; error?: unknown; response?: Response }>): Promise<T> {
  const { data, error, response } = await call;
  if (error !== undefined || !response?.ok) {
    const code = typeof error === 'object' && error && 'code' in error ? String(error.code) : undefined;
    throw new TripsApiError(response?.status, code);
  }
  return data as T;
}

function toOption(dto: RouteOptionDto): RouteOption {
  return {
    rank: dto.rank ?? 0,
    labels: dto.labels ?? [],
    via: dto.via ?? null,
    departureAt: dto.departureAt!,
    arrivalAt: dto.arrivalAt!,
    distanceMeters: dto.distanceMeters ?? 0,
    durationSeconds: dto.durationSeconds ?? 0,
    delaySeconds: dto.delaySeconds ?? 0,
    geometry: (dto.geometry ?? []).map((point) => ({ latitude: point.latitude!, longitude: point.longitude! })),
    waypoints: (dto.waypoints ?? []).map((point) => ({
      latitude: point.latitude!,
      longitude: point.longitude!,
      roadHint: point.roadHint ?? null,
      waypointId: point.waypointId ?? null,
    })),
    conflicts: (dto.conflicts ?? []).map((conflict) => ({
      incidentId: conflict.incidentId!,
      impact: conflict.impact ?? 'DELAYED',
      delaySeconds: conflict.delaySeconds ?? null,
    })),
  };
}

/**
 * Up to three ways between the stops of the route, computed now and not stored.
 *
 * @param departureAt departure to compute from if the start stop has none (not stored)
 */
export async function fetchSuggestions(
  routeId: number,
  options: { includeWaypoints: boolean; departureAt: string | null },
): Promise<RouteSuggestions> {
  const dto = await unwrap(
    suggest({
      path: { routeId },
      body: { count: 3, includeWaypoints: options.includeWaypoints, departureAt: options.departureAt },
    }),
  );
  return { options: (dto.options ?? []).map(toOption), problem: dto.problem ?? null };
}

/** Stores the chosen way: its points replace the waypoints of the route, which is planned again. */
export async function chooseRoute(routeId: number, waypoints: ChoicePoint[]): Promise<PlanOutcome | null> {
  const dto = await unwrap(choose({ path: { routeId }, body: { waypoints } }));
  return dto.planOutcome ?? null;
}

/** Planned ways of the routes of the trip; routes not planned (yet) are missing. */
export async function fetchRouteLines(tripId: number): Promise<RouteLine[]> {
  return (await unwrap(getRouteLines({ path: { tripId } }))).map((dto) => ({
    routeId: dto.routeId!,
    geometry: (dto.geometry ?? []).map((point) => ({ latitude: point.latitude!, longitude: point.longitude! })),
    distanceMeters: dto.distanceMeters ?? 0,
    durationSeconds: dto.durationSeconds ?? 0,
    via: dto.via ?? null,
  }));
}

/** Waypoints the user set on the route (not those created from a suggestion). */
export async function fetchUserWaypointCount(tripId: number, routeId: number): Promise<number> {
  const dto = await unwrap(getRoute({ path: { tripId, routeId } }));
  return (dto.waypoints ?? []).filter((waypoint) => waypoint.origin === 'USER').length;
}
