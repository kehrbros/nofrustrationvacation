import { WarningOutlined } from '@ant-design/icons';
import { useParams } from 'react-router';

import Sheet from '../components/Sheet';
import { t } from '../i18n';
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
      eyebrow={incident ? incidentTypeLabel(incident.type) : t('incident.type.OTHER')}
      title={incident ? incidentTitle(incident) : t('incidentSheet.outdated')}
      footer={
        <button type="button" className="va-button va-button-accent va-block" onClick={close}>
          {t('install.understood')}
        </button>
      }
    >
      {incident && route ? (
        <>
          <div className="va-impact">
            <span className="va-eyebrow">{t('incidentSheet.impact')}</span>
            <span className="va-strong">
              {incident.impact === 'BLOCKED'
                ? t('incidentSheet.routeBlocked', { route: routeName(route, stopById) })
                : t('incidentSheet.routeDelayed', { route: routeName(route, stopById), delay: formatDelay(incident) })}
            </span>
          </div>
          <dl className="va-facts">
            <dt>{t('incidentSheet.valid')}</dt>
            <dd>
              {incident.validTo
                ? t('incidentSheet.validFromTo', { from: formatDateTime(incident.validFrom), to: formatDateTime(incident.validTo) })
                : t('incidentSheet.validFrom', { from: formatDateTime(incident.validFrom) })}
            </dd>
            <dt>{t('incident.delay')}</dt>
            <dd>{formatDelay(incident)}</dd>
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
