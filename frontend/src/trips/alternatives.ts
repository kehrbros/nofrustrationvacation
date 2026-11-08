import { t } from '../i18n';
import type { Booking } from '../bookings/model';
import type { Route, Stop, TripIncident } from './model';

/**
 * Ways out of a consequence on the trip ("TripAlternatives"): another route, another accommodation. Alternatives
 * are computed, never stored; only the option the user picks is saved. Until the backend computes them per
 * consequence, they lead to the dialogs that already calculate them (route suggestions, accommodation search).
 */
export type AlternativeKind = 'ROUTE' | 'ACCOMMODATION';

export interface TripAlternative {
  kind: AlternativeKind;
  title: string;
  detail: string;
  /** Path relative to the trip page, null: not available for this consequence (yet). */
  to: string | null;
}

export interface TripAlternatives {
  consequence: TripIncident;
  route: Route;
  /** Target stop of the affected route. */
  target: Stop | undefined;
  alternatives: TripAlternative[];
}

/** Accommodation the traveller is heading to at the target stop, the earliest check-in first. */
export function targetAccommodation(bookings: Booking[], target: Stop | undefined): Booking | undefined {
  if (!target) {
    return undefined;
  }
  return bookings
    .filter((booking) => booking.stopId === target.id && booking.type === 'ACCOMMODATION' && booking.status === 'CONFIRMED')
    .sort((a, b) => (a.startsAt ?? '').localeCompare(b.startsAt ?? ''))[0];
}

export function tripAlternatives(
  consequence: TripIncident,
  route: Route,
  target: Stop | undefined,
  bookings: Booking[],
): TripAlternatives {
  const blocked = consequence.impact === 'BLOCKED';
  const alternatives: TripAlternative[] = [
    {
      kind: 'ROUTE',
      title: blocked ? t('tripAlternatives.detour') : t('tripAlternatives.otherRoute'),
      detail: blocked ? t('tripAlternatives.detourDetail') : t('tripAlternatives.otherRouteDetail'),
      to: `routen/${route.id}`,
    },
  ];
  const accommodation = targetAccommodation(bookings, target);
  alternatives.push(
    accommodation
      ? {
          kind: 'ACCOMMODATION',
          title: t('tripAlternatives.otherStay'),
          detail:
            consequence.category === 'TOO_LATE'
              ? t('tripAlternatives.stayTooLate', { title: accommodation.title })
              : t('tripAlternatives.stayTight', { title: accommodation.title }),
          to: `buchungen/${accommodation.id}/alternativen`,
        }
      : {
          kind: 'ACCOMMODATION',
          title: t('tripAlternatives.otherStay'),
          detail: target
            ? t('tripAlternatives.noStay', { target: target.name })
            : t('tripAlternatives.noStayAtTarget'),
          to: null,
        },
  );
  return { consequence, route, target, alternatives };
}
