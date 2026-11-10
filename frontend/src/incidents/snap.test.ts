import { describe, expect, it } from 'vitest';

import type { RouteLine } from '../trips/model';
import { metersPerPixel } from '../map/scale';
import { snapToLines } from './snap';

// east-west line at latitude -41 near Wellington
const line = (routeId: number, latitude: number): RouteLine => ({
  routeId,
  geometry: [{ latitude, longitude: 174.70 }, { latitude, longitude: 174.80 }],
  distanceMeters: 8400,
  durationSeconds: 600,
  via: null,
});

describe('snapToLines', () => {
  it('moves a tap next to a route onto it', () => {
    // about 111 m north of the line
    const snapped = snapToLines({ latitude: -40.999, longitude: 174.75 }, [line(1, -41)], 200);
    expect(snapped?.routeId).toBe(1);
    expect(snapped?.point.latitude).toBeCloseTo(-41, 6);
    expect(snapped?.point.longitude).toBeCloseTo(174.75, 6);
    expect(snapped?.distanceMeters).toBeGreaterThan(100);
    expect(snapped?.distanceMeters).toBeLessThan(120);
  });

  it('takes the nearest route and keeps the end of a line', () => {
    const snapped = snapToLines({ latitude: -41.0005, longitude: 174.85 }, [line(1, -41), line(2, -41.01)], 10_000);
    expect(snapped?.routeId).toBe(1);
    expect(snapped?.point.longitude).toBeCloseTo(174.80, 6);
  });

  it('leaves taps far from every route alone', () => {
    expect(snapToLines({ latitude: -40.99, longitude: 174.75 }, [line(1, -41)], 200)).toBeNull();
    expect(snapToLines({ latitude: -41, longitude: 174.75 }, [], 200)).toBeNull();
  });
});

describe('metersPerPixel', () => {
  it('halves with every zoom level', () => {
    expect(metersPerPixel(0, 1)).toBeCloseTo(metersPerPixel(0, 0) / 2, 6);
    expect(metersPerPixel(60, 10)).toBeCloseTo(metersPerPixel(0, 10) / 2, 3);
  });
});
