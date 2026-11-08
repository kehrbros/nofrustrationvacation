/** Where the user is right now, from the browser's Geolocation API, see Docs/architecture.md (Karte). */
export type PositionStatus = 'searching' | 'located' | 'denied' | 'unavailable';

export interface CurrentPosition {
  status: PositionStatus;
  /** Last known position; kept while the signal is lost for a moment (status unavailable). */
  coordinates?: { latitude: number; longitude: number };
}

const PERMISSION_DENIED = 1;

/**
 * Follows the position until the returned function is called. Without the API (or an insecure origin) the status is
 * unavailable. A denied permission ends the watch, the browser would not ask again anyway; a lost signal or a timeout
 * keeps watching and reports unavailable with the last known position.
 */
export function watchCurrentPosition(
  geolocation: Geolocation | undefined,
  listener: (position: CurrentPosition) => void,
): () => void {
  if (!geolocation) {
    listener({ status: 'unavailable' });
    return () => undefined;
  }
  let last: CurrentPosition['coordinates'];
  let watch: number | null = null;
  listener({ status: 'searching' });
  watch = geolocation.watchPosition(
    (position) => {
      last = { latitude: position.coords.latitude, longitude: position.coords.longitude };
      listener({ status: 'located', coordinates: last });
    },
    (error) => {
      if (error.code === PERMISSION_DENIED) {
        if (watch !== null) {
          geolocation.clearWatch(watch);
          watch = null;
        }
        listener({ status: 'denied' });
      } else {
        listener({ status: 'unavailable', coordinates: last });
      }
    },
    { enableHighAccuracy: true, maximumAge: 30_000, timeout: 30_000 },
  );
  return () => {
    if (watch !== null) {
      geolocation.clearWatch(watch);
      watch = null;
    }
  };
}
