import type { TripSummary } from './model';

export interface GroupedTrips {
  /** The trip shown on top: the earliest current one. */
  current: TripSummary | null;
  /** Further current trips and the upcoming ones, ascending by start. */
  upcoming: TripSummary[];
  /** Ascending by start as well. */
  past: TripSummary[];
}

const byStart = (a: TripSummary, b: TripSummary) =>
  new Date(a.startAt).getTime() - new Date(b.startAt).getTime() || a.id - b.id;

/** Splits the trip list as in the UI concept: current trip on top, tabs for upcoming and past trips. */
export function groupTrips(trips: TripSummary[]): GroupedTrips {
  const sorted = [...trips].sort(byStart);
  const current = sorted.filter((trip) => trip.phase === 'CURRENT');
  return {
    current: current[0] ?? null,
    upcoming: sorted.filter((trip) => trip.phase === 'UPCOMING' || (trip.phase === 'CURRENT' && trip !== current[0])),
    past: sorted.filter((trip) => trip.phase === 'PAST'),
  };
}
