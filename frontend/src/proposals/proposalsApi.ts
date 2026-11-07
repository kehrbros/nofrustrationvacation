import {
  accept as acceptRequest,
  dismiss as dismissRequest,
  get as getRunRequest,
  latest as latestRequest,
  latestOfTrip as latestOfTripRequest,
  start as startRequest,
} from '../generated/api';
import type { ProposalDto, ProposalRunDto } from '../generated/api';
import { TripsApiError } from '../trips/tripsApi';

/** Proposals for a route hit by incidents (computed in the background), see Docs/advisor.md#api. */

export type ProposalKind = 'REROUTE' | 'SHIFT_DEPARTURE' | 'KEEP_ROUTE' | 'STOPOVER' | 'TARGET_STAY';
export type RunStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITHOUT_MODEL' | 'FAILED';
export type RunDecision = 'ACCEPTED' | 'DISMISSED';

export interface ProposalStay {
  hotelId: string;
  offerId: string;
  hotelName: string;
  latitude: number;
  longitude: number;
  /** Area or target stop the search looked around. */
  place: string;
  distanceMeters: number;
  /** yyyy-mm-dd */
  checkIn: string;
  checkOut: string;
  guests: number;
  rooms: number;
  /** Total for the stay, decimal as string. */
  price: string;
  currency: string;
  refundable: boolean;
  freeCancellationUntil: string | null;
  replacesBookingId: number | null;
}

export interface Proposal {
  /** 1 is the best. */
  rank: number;
  kind: ProposalKind;
  via: string | null;
  departureAt: string | null;
  arrivalAt: string | null;
  distanceMeters: number | null;
  durationSeconds: number | null;
  delaySeconds: number | null;
  meetsDeadline: boolean | null;
  /** Incidents still passed on this way. */
  conflictCount: number;
  stay: ProposalStay | null;
}

export interface ProposalRun {
  runId: number;
  routeId: number;
  incidentId: number | null;
  status: RunStatus;
  /** The situation of the route changed since, the numbers may be out of date. */
  stale: boolean;
  createdAt: string;
  decision: RunDecision | null;
  chosenRank: number | null;
  proposals: Proposal[];
}

export interface AcceptedProposal {
  kind: ProposalKind;
  plannedRouteIds: number[];
  /** STOPOVER: the new stop at the hotel, TARGET_STAY: the target stop. Book the hotel there next. */
  stayStopId: number | null;
  replacesBookingId: number | null;
  todosAdded: number;
}

async function unwrap<T>(call: Promise<{ data?: T; error?: unknown; response?: Response }>): Promise<T> {
  const { data, error, response } = await call;
  if (error !== undefined || !response?.ok) {
    const code = typeof error === 'object' && error && 'code' in error ? String(error.code) : undefined;
    throw new TripsApiError(response?.status, code);
  }
  return data as T;
}

function toProposal(dto: ProposalDto): Proposal {
  const stay = dto.stay;
  return {
    rank: dto.rank ?? 0,
    kind: (dto.kind ?? 'KEEP_ROUTE') as ProposalKind,
    via: dto.via ?? null,
    departureAt: dto.departureAt ?? null,
    arrivalAt: dto.arrivalAt ?? null,
    distanceMeters: dto.distanceMeters ?? null,
    durationSeconds: dto.durationSeconds ?? null,
    delaySeconds: dto.delaySeconds ?? null,
    meetsDeadline: dto.meetsDeadline ?? null,
    conflictCount: (dto.conflicts ?? []).length,
    stay: stay && stay.hotelId ? {
      hotelId: stay.hotelId,
      offerId: stay.offerId ?? '',
      hotelName: stay.hotelName ?? '',
      latitude: stay.latitude ?? 0,
      longitude: stay.longitude ?? 0,
      place: stay.place ?? '',
      distanceMeters: stay.distanceMeters ?? 0,
      checkIn: stay.checkIn ?? '',
      checkOut: stay.checkOut ?? '',
      guests: stay.guests ?? 1,
      rooms: stay.rooms ?? 1,
      price: stay.price ?? '0',
      currency: stay.currency ?? 'EUR',
      refundable: stay.refundable ?? false,
      freeCancellationUntil: stay.freeCancellationUntil ?? null,
      replacesBookingId: stay.replacesBookingId ?? null,
    } : null,
  };
}

export function toRun(dto: ProposalRunDto): ProposalRun {
  return {
    runId: dto.runId ?? 0,
    routeId: dto.routeId ?? 0,
    incidentId: dto.incidentId ?? null,
    status: (dto.status ?? 'FAILED') as RunStatus,
    stale: dto.stale ?? false,
    createdAt: dto.createdAt ?? '',
    decision: (dto.decision ?? null) as RunDecision | null,
    chosenRank: dto.chosenRank ?? null,
    proposals: (dto.proposals ?? []).map(toProposal),
  };
}

/** The last run of the route, null without. */
export async function fetchRouteRun(routeId: number): Promise<ProposalRun | null> {
  const dto = await unwrap(latestRequest({ path: { routeId } }));
  return dto.run ? toRun(dto.run) : null;
}

/** The last run of every route of the trip that has one. */
export async function fetchTripRuns(tripId: number): Promise<ProposalRun[]> {
  return ((await unwrap(latestOfTripRequest({ path: { tripId } }))).runs ?? []).map(toRun);
}

export async function fetchRun(runId: number): Promise<ProposalRun> {
  return toRun(await unwrap(getRunRequest({ path: { runId } })));
}

/** Starts the search for solutions in the background (or returns the one running); poll {@link fetchRun}. */
export async function startRun(routeId: number, incidentId: number): Promise<number> {
  return (await unwrap(startRequest({ path: { routeId }, body: { incidentId } }))).runId ?? 0;
}

/** Takes the proposal, the trip is changed accordingly. */
export async function acceptProposal(runId: number, rank: number): Promise<AcceptedProposal> {
  const dto = await unwrap(acceptRequest({ path: { runId, rank } }));
  return {
    kind: (dto.kind ?? 'KEEP_ROUTE') as ProposalKind,
    plannedRouteIds: dto.plannedRouteIds ?? [],
    stayStopId: dto.stayStopId ?? null,
    replacesBookingId: dto.replacesBookingId ?? null,
    todosAdded: dto.todosAdded ?? 0,
  };
}

/** None fits, the user plans again by hand. */
export async function dismissProposals(runId: number): Promise<ProposalRun> {
  return toRun(await unwrap(dismissRequest({ path: { runId } })));
}

export function isRunning(run: ProposalRun | null): boolean {
  return run !== null && (run.status === 'PENDING' || run.status === 'RUNNING');
}

/** Finished with something to choose and not decided yet: worth a hint on the trip. */
export function isOpen(run: ProposalRun | null): boolean {
  return run !== null && (run.status === 'COMPLETED' || run.status === 'COMPLETED_WITHOUT_MODEL')
    && run.decision === null && !run.stale && run.proposals.length > 0;
}
