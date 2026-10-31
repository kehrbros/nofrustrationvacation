import { WarningOutlined } from '@ant-design/icons';
import { useParams } from 'react-router';

import Sheet from '../components/Sheet';
import { descriptionIsTitle, formatDelay, incidentTitle, incidentTypeLabel } from '../trips/incidentText';
import { routeName, useTripContext } from '../trips/tripContext';
import { formatAgo, formatDateTime } from '../trips/time';

/** Incident as overlay dialog over the map: what it means for the trip, validity, delay, description. */
export default function IncidentSheet() {
  const { routeId, incidentId } = useParams();
  const { incidents, trip, stopById, close } = useTripContext();
  const incident = incidents.find(
    (candidate) => candidate.routeId === Number(routeId) && candidate.incidentId === Number(incidentId),
  );
  const route = trip.routes.find((candidate) => candidate.id === Number(routeId));

  return (
    <Sheet
      open
      onClose={close}
      icon={<span className="va-warning-icon"><WarningOutlined /></span>}
      eyebrow={incident ? incidentTypeLabel(incident.type) : 'Hinweis'}
      title={incident ? incidentTitle(incident) : 'Hinweis nicht mehr aktuell'}
      footer={
        <button type="button" className="va-button va-button-accent va-block" onClick={close}>
          Verstanden
        </button>
      }
    >
      {incident && route ? (
        <>
          <div className="va-impact">
            <span className="va-eyebrow">Auswirkung auf deine Reise</span>
            <span className="va-strong">
              {routeName(route, stopById)}{' '}
              {incident.impact === 'BLOCKED' ? 'ist gesperrt' : `verzögert sich (${formatDelay(incident)})`}
            </span>
          </div>
          <dl className="va-facts">
            <dt>Gültig</dt>
            <dd>
              ab {formatDateTime(incident.validFrom)}, {incident.validTo ? `bis ${formatDateTime(incident.validTo)}` : 'bis auf Weiteres'}
            </dd>
            <dt>Verzögerung</dt>
            <dd>{formatDelay(incident)}</dd>
            {incident.description && !descriptionIsTitle(incident) && (
              <>
                <dt>Beschreibung</dt>
                <dd>{incident.description}</dd>
              </>
            )}
            <dt>Stand</dt>
            <dd>aktualisiert {formatAgo(incident.updatedAt)}</dd>
          </dl>
        </>
      ) : (
        <p className="va-muted">Dieser Hinweis betrifft deine Reise nicht mehr.</p>
      )}
    </Sheet>
  );
}
