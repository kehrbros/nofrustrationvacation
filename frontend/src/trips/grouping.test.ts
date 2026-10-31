import { describe, expect, it } from 'vitest';

import { groupTrips } from './grouping';
import type { TripPhase, TripSummary } from './model';

function trip(id: number, phase: TripPhase, startAt: string): TripSummary {
  return { id, name: `Reise ${id}`, startAt, endAt: startAt, phase, stopCount: 0, nextStopName: null, hasCoverImage: false, incidentCount: 0 };
}

describe('groupTrips', () => {
  it('puts the earliest current trip on top and further current ones into upcoming, all ascending by start', () => {
    const grouped = groupTrips([
      trip(1, 'UPCOMING', '2027-09-03T00:00:00+02:00'),
      trip(2, 'CURRENT', '2027-07-12T00:00:00+02:00'),
      trip(3, 'PAST', '2027-05-14T00:00:00+02:00'),
      trip(4, 'CURRENT', '2027-07-13T00:00:00+02:00'),
      trip(5, 'PAST', '2027-03-18T00:00:00+01:00'),
    ]);

    expect(grouped.current?.id).toBe(2);
    expect(grouped.upcoming.map((t) => t.id)).toEqual([4, 1]);
    expect(grouped.past.map((t) => t.id)).toEqual([5, 3]);
  });

  it('has no current trip when none is running', () => {
    expect(groupTrips([trip(1, 'UPCOMING', '2027-09-03T00:00:00+02:00')]).current).toBeNull();
  });
});
