import type { LinePoint, RouteLine } from '../trips/model';

const EARTH_RADIUS = 6_371_000;
/** A tap this close to a route (on screen) snaps onto it. */
export const SNAP_PIXELS = 60;

export interface Snapped {
  point: LinePoint;
  routeId: number;
  distanceMeters: number;
}

/**
 * Nearest point on the planned ways of the trip, if within maxMeters of the tap; null otherwise. The lines follow the
 * road segments, so a point on them is matched by the backend (segments within 50 m). Locally flat projection around
 * the tap, exact enough for a few hundred metres.
 */
export function snapToLines(tap: LinePoint, lines: RouteLine[], maxMeters: number): Snapped | null {
  const scaleX = ((Math.PI / 180) * EARTH_RADIUS) * Math.cos((tap.latitude * Math.PI) / 180);
  const scaleY = (Math.PI / 180) * EARTH_RADIUS;
  const toXY = (point: LinePoint) => [(point.longitude - tap.longitude) * scaleX, (point.latitude - tap.latitude) * scaleY];
  let best: Snapped | null = null;
  for (const line of lines) {
    for (let index = 1; index < line.geometry.length; index++) {
      const [ax, ay] = toXY(line.geometry[index - 1]);
      const [bx, by] = toXY(line.geometry[index]);
      const dx = bx - ax;
      const dy = by - ay;
      const lengthSquared = dx * dx + dy * dy;
      // tap is the origin: share of the segment closest to it
      const share = lengthSquared === 0 ? 0 : Math.min(1, Math.max(0, -(ax * dx + ay * dy) / lengthSquared));
      const x = ax + share * dx;
      const y = ay + share * dy;
      const distance = Math.hypot(x, y);
      if (distance <= maxMeters && (!best || distance < best.distanceMeters)) {
        best = {
          point: { latitude: tap.latitude + y / scaleY, longitude: tap.longitude + x / scaleX },
          routeId: line.routeId,
          distanceMeters: distance,
        };
      }
    }
  }
  return best;
}
