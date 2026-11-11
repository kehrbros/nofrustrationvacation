import {
  abort as abortPlanRequest,
  accept as acceptRequest,
  checkout as planCheckoutRequest,
  complete as completePlanRequest,
  execution as planExecutionRequest,
  dismiss as dismissRequest,
  get as getRunRequest,
  latest as latestRequest,
  latestOfTrip as latestOfTripRequest,
  start as startRequest,
} from '../generated/api';
import type { PlanExecutionDto, ProposalDto, ProposalRunDto, ProposalStayDto } from '../generated/api';
import type { Holder } from '../bookings/alternativesApi';
import { TripsApiError } from '../trips/tripsApi';

/** Proposals for a route hit by incidents (computed in the background), see Docs/advisor.md#api. */

export type ProposalKind = 'REROUTE' | 'SHIFT_DEPARTURE' | 'KEEP_ROUTE' | 'STOPOVER' | 'TARGET_STAY' | 'PLAN';
export type PlanStepKind = 'DRIVE' | 'OVERNIGHT' | 'CONTINUE' | 'KEEP_BOOKING' | 'REPLACE_STAY' | 'NEW_TARGET';
export type PlanExecutionStatus = 'AWAITING_PAYMENT' | 'APPLYING' | 'APPLIED' | 'FAILED' | 'APPLIED_PARTLY';
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

/** A step of a plan, in order, see Docs/incident-plans.md. */
export interface PlanStep {
  kind: PlanStepKind;
  routeKind: ProposalKind | null;
  via: string | null;
  departureAt: string | null;
  arrivalAt: string | null;
  /** CONTINUE: leaves when a closure ends. */
  waitedFor: string | null;
  distanceMeters: number | null;
  placeName: string | null;
  /** KEEP_BOOKING: the accommodation is told the guests come on this day (yyyy-mm-dd), null if only late. */
  arrivalOn: string | null;
  stay: ProposalStay | null;
}

/** What a plan leads to. */
export interface PlanResult {
  deadline: string | null;
  reachesTarget: boolean;
  extraNights: number;
  /** Hotels to pay, decimal as string. */
  extraCost: string;
  costCurrency: string | null;
  affectsFollowing: boolean;
  newTargetName: string | null;
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
  /** PLAN only. */
  plan: PlanResult | null;
  steps: PlanStep[];
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

function toStay(stay: ProposalStayDto | undefined): ProposalStay | null {
  return stay && stay.hotelId ? {
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
  } : null;
}

function toProposal(dto: ProposalDto): Proposal {
  const plan = dto.plan;
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
    stay: toStay(dto.stay),
    plan: plan && plan.extraCost !== undefined ? {
      deadline: plan.deadline ?? null,
      reachesTarget: plan.reachesTarget ?? true,
      extraNights: plan.extraNights ?? 0,
      extraCost: plan.extraCost ?? '0',
      costCurrency: plan.costCurrency ?? null,
      affectsFollowing: plan.affectsFollowing ?? false,
      newTargetName: plan.newTargetName ?? null,
    } : null,
    steps: (dto.steps ?? []).map((step) => ({
      kind: (step.kind ?? 'DRIVE') as PlanStepKind,
      routeKind: (step.routeKind ?? null) as ProposalKind | null,
      via: step.via ?? null,
      departureAt: step.departureAt ?? null,
      arrivalAt: step.arrivalAt ?? null,
      waitedFor: step.waitedFor ?? null,
      distanceMeters: step.distanceMeters ?? null,
      placeName: step.placeName ?? null,
      arrivalOn: step.arrivalOn ?? null,
      stay: toStay(step.stay),
    })),
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

/** Hotels of a plan, paid together with one PayPal approval. */
export function planStays(proposal: Proposal): ProposalStay[] {
  return proposal.steps.flatMap((step) => (step.stay ? [step.stay] : []));
}

export interface PlanHotel {
  hotelName: string;
  price: string;
  currency: string;
  status: string;
  bookingId: number | null;
}

/** Taking a plan with hotels, see Docs/incident-plans.md#one-click-execution. */
export interface PlanExecution {
  executionId: number;
  tripId: number;
  status: PlanExecutionStatus;
  failure: string | null;
  orderId: string | null;
  approveUrl: string | null;
  total: string | null;
  currency: string | null;
  hotels: PlanHotel[];
  todosAdded: number;
}

function toExecution(dto: PlanExecutionDto): PlanExecution {
  return {
    executionId: dto.executionId ?? 0,
    tripId: dto.tripId ?? 0,
    status: (dto.status ?? 'FAILED') as PlanExecutionStatus,
    failure: dto.failure ?? null,
    orderId: dto.orderId ?? null,
    approveUrl: dto.approveUrl ?? null,
    total: dto.total ?? null,
    currency: dto.currency ?? null,
    hotels: (dto.hotels ?? []).map((hotel) => ({
      hotelName: hotel.hotelName ?? '',
      price: String(hotel.price ?? '0'),
      currency: hotel.currency ?? '',
      status: hotel.status ?? '',
      bookingId: hotel.bookingId ?? null,
    })),
    todosAdded: dto.todosAdded ?? 0,
  };
}

/** Locks the prices of all hotels of the plan and creates one PayPal order for them. */
export async function startPlanCheckout(runId: number, rank: number, holder: Holder): Promise<PlanExecution> {
  return toExecution(await unwrap(planCheckoutRequest({ path: { runId, rank }, body: holder })));
}

/** After the approval at PayPal: books the hotels, changes the trip and pays. */
export async function completePlan(executionId: number): Promise<PlanExecution> {
  return toExecution(await unwrap(completePlanRequest({ path: { executionId } })));
}

export async function abortPlan(executionId: number): Promise<PlanExecution> {
  return toExecution(await unwrap(abortPlanRequest({ path: { executionId } })));
}

export async function fetchPlanExecution(executionId: number): Promise<PlanExecution> {
  return toExecution(await unwrap(planExecutionRequest({ path: { executionId } })));
}
