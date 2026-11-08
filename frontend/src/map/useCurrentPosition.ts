import { useEffect, useState } from 'react';

import { type CurrentPosition, watchCurrentPosition } from './position';

/** The user's position, followed while the component is mounted. */
export function useCurrentPosition(): CurrentPosition {
  const [position, setPosition] = useState<CurrentPosition>({ status: 'searching' });
  useEffect(() => watchCurrentPosition(typeof navigator === 'undefined' ? undefined : navigator.geolocation, setPosition), []);
  return position;
}
