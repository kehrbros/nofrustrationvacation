import { describe, expect, it, vi } from 'vitest';

import { type CurrentPosition, watchCurrentPosition } from './position';

function fakeGeolocation() {
  let success: PositionCallback = () => undefined;
  let failure: PositionErrorCallback = () => undefined;
  const geolocation = {
    watchPosition: vi.fn((onSuccess: PositionCallback, onError: PositionErrorCallback) => {
      success = onSuccess;
      failure = onError;
      return 7;
    }),
    clearWatch: vi.fn(),
    getCurrentPosition: vi.fn(),
  };
  return {
    geolocation: geolocation as unknown as Geolocation,
    clearWatch: geolocation.clearWatch,
    fix: (latitude: number, longitude: number) => success({ coords: { latitude, longitude } } as GeolocationPosition),
    fail: (code: number) => failure({ code } as GeolocationPositionError),
  };
}

describe('watchCurrentPosition', () => {
  it('is unavailable without the Geolocation API', () => {
    const seen: CurrentPosition[] = [];
    watchCurrentPosition(undefined, (position) => seen.push(position));
    expect(seen).toEqual([{ status: 'unavailable' }]);
  });

  it('follows the position and keeps the last one while the signal is lost', () => {
    const fake = fakeGeolocation();
    const seen: CurrentPosition[] = [];
    const stop = watchCurrentPosition(fake.geolocation, (position) => seen.push(position));
    fake.fix(-43.5, 172.6);
    fake.fail(2);
    expect(seen).toEqual([
      { status: 'searching' },
      { status: 'located', coordinates: { latitude: -43.5, longitude: 172.6 } },
      { status: 'unavailable', coordinates: { latitude: -43.5, longitude: 172.6 } },
    ]);
    expect(fake.clearWatch).not.toHaveBeenCalled();
    stop();
    expect(fake.clearWatch).toHaveBeenCalledWith(7);
  });

  it('stops watching when the permission is denied', () => {
    const fake = fakeGeolocation();
    const seen: CurrentPosition[] = [];
    const stop = watchCurrentPosition(fake.geolocation, (position) => seen.push(position));
    fake.fail(1);
    expect(seen.at(-1)).toEqual({ status: 'denied' });
    expect(fake.clearWatch).toHaveBeenCalledTimes(1);
    stop();
    expect(fake.clearWatch).toHaveBeenCalledTimes(1);
  });
});
