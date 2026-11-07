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
  const move = `${formatDistance(check.distanceMeters)} vom bisherigen Stop entfernt. Nach der Buchung wird der Stop `
    + 'dorthin verlegt und die Route neu geplant.';
  if (!check.arrivalAt) {
    return { move, arrival: null, warning: null };
  }
  const delay = check.delaySeconds >= 60 ? ` (inkl. ${formatDuration(check.delaySeconds)} Verzögerung)` : '';
  const checkIn = check.checkInUntil ? `, Check-in bis ${formatTime(check.checkInUntil)}` : '';
  const arrival = `Ankunft ca. ${formatTime(check.arrivalAt)}${delay}${checkIn}`;
  const warning = check.blocked
    ? 'Auch der beste Weg dorthin führt durch eine Sperrung.'
    : check.late && check.checkInUntil
      ? `Du kommst voraussichtlich erst nach dem Check-in-Ende (${formatTime(check.checkInUntil)}) an. Frag beim Hotel `
        + 'nach einem späten Check-in oder wähle eine Unterkunft näher an der Route.'
      : null;
  return { move, arrival, warning };
}
