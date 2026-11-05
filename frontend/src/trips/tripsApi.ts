import {
  addStop as addStopRequest,
  createTrip as createTripRequest,
  generateCoverImage as generateCoverImageRequest,
  getCoverImage,
  getTrip,
  getTripIncidentCounts,
  getTripIncidents,
  listTrips,
  uploadCoverImage as uploadCoverImageRequest,
} from '../generated/api';
import type {
  RouteDto,
  StopDto,
  TripDetailDto,
  TripIncidentDto,
  TripSummaryDto,
} from '../generated/api';
import type { NewStop, NewTrip, Route, Stop, TripDetail, TripIncident, TripSummary } from './model';

/** Error of a trips API call, with the problem code of the backend if there is one. */
export class TripsApiError extends Error {
  constructor(
    readonly status: number | undefined,
    readonly code?: string,
  ) {
    super(code ?? `request failed${status ? ` (${status})` : ''}`);
  }
}

async function unwrap<T>(call: Promise<{ data?: T; error?: unknown; response?: Response }>): Promise<T> {
  const { data, error, response } = await call;
  if (error !== undefined || !response?.ok) {
    const code = typeof error === 'object' && error && 'code' in error ? String(error.code) : undefined;
    throw new TripsApiError(response?.status, code);
  }
  return data as T;
}

function toStop(dto: StopDto): Stop {
  return {
    id: dto.id!,
    position: dto.position ?? 0,
    name: dto.name ?? '',
    latitude: dto.latitude ?? null,
    longitude: dto.longitude ?? null,
    arrivalAt: dto.arrivalAt ?? null,
    departureAt: dto.departureAt ?? null,
  };
}

function toRoute(dto: RouteDto): Route {
  return {
    id: dto.id!,
    fromStopId: dto.fromStopId!,
    toStopId: dto.toStopId!,
    plannedAt: dto.plannedAt ?? null,
    planOutcome: dto.planOutcome ?? null,
    planReason: dto.planReason ?? null,
  };
}

function toDetail(dto: TripDetailDto): TripDetail {
  return {
    id: dto.id!,
    name: dto.name ?? '',
    startAt: dto.startAt!,
    endAt: dto.endAt!,
    phase: dto.phase ?? 'UPCOMING',
    travelers: dto.travelers ?? null,
    hasCoverImage: dto.hasCoverImage ?? false,
    stops: (dto.stops ?? []).map(toStop).sort((a, b) => a.position - b.position),
    routes: (dto.routes ?? []).map(toRoute),
  };
}

function toIncident(dto: TripIncidentDto): TripIncident {
  return {
    incidentId: dto.incidentId!,
    routeId: dto.routeId!,
    type: dto.type ?? 'OTHER',
    description: dto.description ?? null,
    impact: dto.impact ?? 'DELAYED',
    delayMinutes: dto.delayMinutes ?? null,
    validFrom: dto.validFrom!,
    validTo: dto.validTo ?? null,
    detectedAt: dto.detectedAt!,
    updatedAt: dto.updatedAt!,
  };
}

/** All trips of the user with their incident counts, ascending by start. */
export async function fetchTrips(): Promise<TripSummary[]> {
  const [trips, counts] = await Promise.all([unwrap(listTrips()), unwrap(getTripIncidentCounts())]);
  const countByTrip = new Map((counts ?? []).map((count) => [count.tripId!, count.count ?? 0]));
  return (trips ?? []).map((dto: TripSummaryDto) => ({
    id: dto.id!,
    name: dto.name ?? '',
    startAt: dto.startAt!,
    endAt: dto.endAt!,
    phase: dto.phase ?? 'UPCOMING',
    stopCount: dto.stopCount ?? 0,
    nextStopName: dto.nextStopName ?? null,
    hasCoverImage: dto.hasCoverImage ?? false,
    incidentCount: countByTrip.get(dto.id!) ?? 0,
  }));
}

export async function fetchTrip(tripId: number): Promise<TripDetail> {
  return toDetail(await unwrap(getTrip({ path: { tripId } })));
}

export async function fetchTripIncidents(tripId: number): Promise<TripIncident[]> {
  return (await unwrap(getTripIncidents({ path: { tripId } }))).map(toIncident);
}

export async function createTrip(trip: NewTrip): Promise<TripDetail> {
  return toDetail(await unwrap(createTripRequest({ body: trip })));
}

/** Appends the stop; the response is the whole trip, since routes are created and planned with it. */
export async function addStop(tripId: number, stop: NewStop): Promise<TripDetail> {
  return toDetail(await unwrap(addStopRequest({ path: { tripId }, body: stop })));
}

export async function uploadCoverImage(tripId: number, file: File): Promise<void> {
  await unwrap(uploadCoverImageRequest({ path: { tripId }, body: { file } }));
}

/** Lets the image model draw a title photo from name and stops; takes a while, keeps an uploaded photo. */
export async function generateCoverImage(tripId: number): Promise<void> {
  await unwrap(generateCoverImageRequest({ path: { tripId } }));
}

/** Title photo as blob (the request needs the access token, so no plain img src), null if the trip has none. */
export async function fetchCoverImage(tripId: number): Promise<Blob | null> {
  const { data, response } = await getCoverImage({ path: { tripId }, parseAs: 'blob' });
  if (response?.status === 404 || !response?.ok) {
    return null;
  }
  return data as unknown as Blob;
}
