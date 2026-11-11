import type { Criteria } from './alternativesApi';

/** yyyy-mm-dd plus whole days, no time zone involved */
export function addDays(day: string, days: number): string {
  const [year, month, date] = day.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, date + days)).toISOString().slice(0, 10);
}

function nights(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

/**
 * Why the dates cannot be searched: check-in before the earliest day, with nights left from the earliest day on
 * ('startsEarlier') or none ('over'). Null if the dates are fine or not entered yet.
 */
export function dateProblem(criteria: Pick<Criteria, 'checkIn' | 'checkOut' | 'earliestCheckIn'>): 'startsEarlier' | 'over' | null {
  const { checkIn, checkOut, earliestCheckIn } = criteria;
  if (!checkIn || !earliestCheckIn || checkIn >= earliestCheckIn) {
    return null;
  }
  return checkOut && checkOut > earliestCheckIn ? 'startsEarlier' : 'over';
}

/** The nights from the earliest day on, the maximum price reduced to them per night. */
export function fromEarliest(criteria: Criteria): Criteria {
  if (dateProblem(criteria) !== 'startsEarlier') {
    return criteria;
  }
  const booked = nights(criteria.checkIn, criteria.checkOut);
  const left = nights(criteria.earliestCheckIn, criteria.checkOut);
  const price = Number(criteria.maxPrice.replace(',', '.'));
  const maxPrice = criteria.maxPrice.trim() && price > 0 && booked > 0
    ? (Math.ceil((price * left / booked) * 100) / 100).toFixed(2)
    : criteria.maxPrice;
  return { ...criteria, checkIn: criteria.earliestCheckIn, maxPrice };
}

/** Earliest check-out day the picker offers: the day after check-in, never before the day after the earliest day. */
export function earliestCheckOut(criteria: Pick<Criteria, 'checkIn' | 'earliestCheckIn'>): string | undefined {
  const from = [criteria.checkIn, criteria.earliestCheckIn].filter(Boolean).sort().pop();
  return from ? addDays(from, 1) : undefined;
}
