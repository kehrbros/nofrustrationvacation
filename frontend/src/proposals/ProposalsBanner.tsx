import { BulbOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { Link } from 'react-router';

import type { TripDetail, TripIncident } from '../trips/model';
import { routeName } from '../trips/tripContext';
import type { Stop } from '../trips/model';
import { fetchTripRuns, isOpen, type ProposalRun } from './proposalsApi';

/**
 * Banner over the map when solutions for a route are ready and the user has not decided yet, e.g. found while the
 * dialog was closed. Tapping opens them. Loaded again with the trip.
 */
export default function ProposalsBanner({ trip, incidentsByRoute, stopById }: {
  trip: TripDetail;
  incidentsByRoute: Map<number, TripIncident[]>;
  stopById: Map<number, Stop>;
}) {
  const [runs, setRuns] = useState<ProposalRun[]>([]);

  useEffect(() => {
    let active = true;
    fetchTripRuns(trip.id).then((loaded) => active && setRuns(loaded), () => undefined);
    return () => {
      active = false;
    };
  }, [trip]);

  const open = runs.find((run) => isOpen(run) && incidentsByRoute.has(run.routeId));
  if (!open) {
    return null;
  }
  const route = trip.routes.find((candidate) => candidate.id === open.routeId);
  const incidents = incidentsByRoute.get(open.routeId) ?? [];
  const incident = incidents.find((candidate) => candidate.incidentId === open.incidentId) ?? incidents[0];
  if (!route || !incident) {
    return null;
  }
  const count = open.proposals.length;
  return (
    <Link className="va-trip-banner" to={`alternativen/${route.id}/${incident.incidentId}`} role="status">
      <BulbOutlined aria-hidden="true" />
      <span className="va-column">
        <span className="va-strong">{count === 1 ? '1 Lösung' : `${count} Lösungen`} für {routeName(route, stopById)}</span>
        <span className="va-small">Tippen zum Ansehen und Übernehmen.</span>
      </span>
    </Link>
  );
}
