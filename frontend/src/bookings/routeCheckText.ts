import { t } from '../i18n';
import { formatDistance, formatDuration } from '../trips/routeOptionText';
import { formatTime } from '../trips/time';
import type { RouteCheck } from './alternativesApi';

/**
 * Texts of the route check before paying for an alternative in another place, see
 * Docs/accommodation-alternatives.md#route-vor-dem-buchen-pruefen. Null where nothing is to be said.
 */
export interface RouteCheckText {
  /** "26 km vom bisherigen Stop. Nach der Buchung wird der Stop dorthin verlegt und die Route neu geplant." */
  move: string;
  /** "Ankunft ca. 19:40 (inkl. 25 min Verzögerung), Check-in bis 22:00" */
  arrival: string | null;
  /** Problem with the way, shown as a warning. */
  warning: string | null;
}

export function routeCheckText(check: RouteCheck | null): RouteCheckText | null {
  if (!check?.otherPlace) {
    return null;
  }
  const move = t('routeCheck.move', { distance: formatDistance(check.distanceMeters) });
  if (!check.arrivalAt) {
    return { move, arrival: null, warning: null };
  }
  const delay = check.delaySeconds >= 60 ? t('routeCheck.delay', { duration: formatDuration(check.delaySeconds) }) : '';
  const checkIn = check.checkInUntil ? t('routeCheck.checkIn', { time: formatTime(check.checkInUntil) }) : '';
  const arrival = t('routeCheck.arrival', { time: formatTime(check.arrivalAt), delay, checkIn });
  const warning = check.blocked
    ? t('routeCheck.blocked')
    : check.late && check.checkInUntil
      ? t('routeCheck.late', { time: formatTime(check.checkInUntil) })
      : null;
  return { move, arrival, warning };
}
