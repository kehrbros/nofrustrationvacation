import { CheckCircleFilled, WarningOutlined } from '@ant-design/icons';
import { useParams } from 'react-router';

import Sheet from '../components/Sheet';
import { t } from '../i18n';
import { descriptionIsTitle, formatDelay, incidentTitle, incidentTypeLabel } from '../trips/incidentText';
import { routeName, useTripContext } from '../trips/tripContext';
import { formatAgo, formatDateTime } from '../trips/time';

/**
 * Incident as overlay dialog over the map: what it means for the trip, validity, delay, description. A resolved
 * incident (ended) is shown in green and can be removed from the map.
 */
export default function IncidentSheet() {
  const { routeId, incidentId } = useParams();
  const { incidents, resolvedIncidents, removeResolvedIncident, trip, stopById, close } = useTripContext();
  const matches = (candidate: { routeId: number; incidentId: number }) =>
    candidate.routeId === Number(routeId) && candidate.incidentId === Number(incidentId);
  const incident = incidents.find(matches) ?? resolvedIncidents.find(matches);
  const route = trip.routes.find((candidate) => candidate.id === Number(routeId));
  const resolved = incident?.resolved ?? false;

  // optimistic: the sheet closes and the icon leaves the map at once, a failure brings it back (TripPage)
  const remove = () => {
    if (!incident) {
      return;
    }
    close();
    removeResolvedIncident(incident);
  };

  return (
    <Sheet
      open
      onClose={close}
      icon={
        resolved
          ? <span className="va-resolved-icon"><CheckCircleFilled /></span>
          : <span className="va-warning-icon"><WarningOutlined /></span>
      }
      eyebrow={incident ? incidentTypeLabel(incident.type) : t('incident.type.OTHER')}
      title={incident ? incidentTitle(incident) : t('incidentSheet.outdated')}
      footer={
        resolved ? (
          <div className="va-column va-sheet-actions">
            <button type="button" className="va-button va-button-accent va-block" onClick={remove}>
              {t('incidentSheet.remove')}
            </button>
            <button type="button" className="va-button va-button-outline va-block" onClick={close}>
              {t('incidentSheet.keep')}
            </button>
          </div>
        ) : (
          <button type="button" className="va-button va-button-accent va-block" onClick={close}>
            {t('install.understood')}
          </button>
        )
      }
    >
      {incident && route ? (
        <>
          {resolved ? (
            <div className="va-impact va-impact-resolved" role="status">
              <span className="va-eyebrow">{routeName(route, stopById)}</span>
              <span className="va-strong">{t('incidentSheet.resolved')}</span>
              <span className="va-small">
                {incident.resolvedAt
                  ? t('incidentSheet.resolvedDetailAgo', { ago: formatAgo(incident.resolvedAt) })
                  : t('incidentSheet.resolvedDetail')}
              </span>
            </div>
          ) : (
            <div className="va-impact">
              <span className="va-eyebrow">{t('incidentSheet.impact')}</span>
              <span className="va-strong">
                {incident.impact === 'BLOCKED'
                  ? t('incidentSheet.routeBlocked', { route: routeName(route, stopById) })
                  : t('incidentSheet.routeDelayed', { route: routeName(route, stopById), delay: formatDelay(incident) })}
              </span>
            </div>
          )}
          <dl className="va-facts">
            <dt>{t('incidentSheet.valid')}</dt>
            <dd>
              {incident.validTo
                ? t('incidentSheet.validFromTo', { from: formatDateTime(incident.validFrom), to: formatDateTime(incident.validTo) })
                : t('incidentSheet.validFrom', { from: formatDateTime(incident.validFrom) })}
            </dd>
            {!resolved && (
              <>
                <dt>{t('incident.delay')}</dt>
                <dd>{formatDelay(incident)}</dd>
              </>
            )}
            {incident.description && !descriptionIsTitle(incident) && (
              <>
                <dt>{t('incidentSheet.description')}</dt>
                <dd>{incident.description}</dd>
              </>
            )}
            <dt>{t('incidentSheet.asOf')}</dt>
            <dd>{t('incidentSheet.updated', { ago: formatAgo(incident.updatedAt) })}</dd>
          </dl>
        </>
      ) : (
        <p className="va-muted">{t('incidentSheet.notAffected')}</p>
      )}
    </Sheet>
  );
}
