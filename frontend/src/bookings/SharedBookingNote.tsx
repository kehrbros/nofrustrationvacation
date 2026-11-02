import { useEffect, useState } from 'react';
import { Link } from 'react-router';

import { readShared, SHARE_PATH } from './shareTarget';

/** Hint on the trip list when a shared confirmation is still waiting, e.g. after the login interrupted sharing. */
export default function SharedBookingNote() {
  const [waiting, setWaiting] = useState(false);
  useEffect(() => {
    readShared().then((shared) => setWaiting(shared !== null), () => undefined);
  }, []);
  if (!waiting) {
    return null;
  }
  return (
    <p className="va-note">
      <strong>Eine geteilte Buchung wartet.</strong> <Link to={SHARE_PATH}>Jetzt prüfen und speichern</Link>
    </p>
  );
}
