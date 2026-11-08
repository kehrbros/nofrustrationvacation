import { formatNumber, t } from '../i18n';
import type { RouteOption, Stop, TripDetail } from './model';
import { toOffsetIso } from './time';

/** Texts of route suggestions; the backend sends labels, the texts are built here (Docs/recommendations.md). */

/** "3 h 40 min", "45 min" */
export function formatDuration(seconds: number): string {
  const minutes = Math.round(seconds / 60);
  const hours = Math.floor(minutes / 60);
  return hours === 0 ? `${minutes} min` : `${hours} h ${String(minutes % 60).padStart(2, '0')} min`;
}

/** "128 km", "4,2 km" below 10 km */
export function formatDistance(meters: number): string {
  const km = meters / 1000;
  return km < 10 ? `${formatNumber(km, { maximumFractionDigits: 1 })} km` : `${Math.round(km)} km`;
}

/** "Schnellste · über SH 1", "Kürzeste", "Alternative über SH 7", "Alternative 2" */
export function optionTitle(option: RouteOption): string {
  const kind = option.labels.includes('FASTEST')
    ? t('routeOption.fastest')
    : option.labels.includes('SHORTEST')
      ? t('routeOption.shortest')
      : null;
  if (kind) {
    return option.via ? t('routeOption.kindVia', { kind, via: option.via }) : kind;
  }
  return option.via ? t('routeOption.alternativeVia', { via: option.via }) : t('routeOption.alternative', { rank: option.rank });
}

/** Time lost against the fastest option, e.g. "+18 min"; null for the fastest itself or under a minute. */
export function slowerBy(option: RouteOption, fastest: RouteOption): string | null {
  const seconds = new Date(option.arrivalAt).getTime() - new Date(fastest.arrivalAt).getTime();
  return seconds >= 60_000 ? `+${formatDuration(seconds / 1000)}` : null;
}

/**
 * Departure to compute suggestions from when the start stop has none: arrival at the stop, otherwise 09:00 on the
 * first day of the trip. Not stored; arrival times computed from it are not shown. Null if the stop has a departure.
 */
export function substituteDeparture(trip: TripDetail, from: Stop | undefined): string | null {
  if (from?.departureAt) {
    return null;
  }
  if (from?.arrivalAt) {
    return from.arrivalAt;
  }
  const start = new Date(trip.startAt);
  start.setHours(9, 0, 0, 0);
  return toOffsetIso(start);
}
