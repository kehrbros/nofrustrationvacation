import { HomeOutlined, NodeIndexOutlined, RightOutlined, WarningOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { Link, useParams } from 'react-router';

import { useAsync } from '../api/useAsync';
import Sheet from '../components/Sheet';
import { t } from '../i18n';
import { HOME_PATH } from '../layout/navigation';
import ProposalsPanel from '../proposals/ProposalsPanel';
import { tripAlternatives, type TripAlternative } from '../trips/alternatives';
import { categoryLabel, consequenceText, formatMinutes, incidentTitle, incidentTypeLabel } from '../trips/incidentText';
import type { RouteIncidentSummary } from '../trips/model';
import { routeName, useTripContext } from '../trips/tripContext';
import { formatAgo } from '../trips/time';
import { fetchRouteSummaries } from '../trips/tripsApi';

/**
 * Opens when a consequence on the map is tapped: what it means for the trip, the solutions found for it (proposals,
 * taken with one tap) and, below, the ways to plan again by hand (another route, another accommodation).
 */
export default function TripAlternativesSheet() {
  const { routeId, incidentId } = useParams();
  const { trip, incidents, stopById, bookings, reload, close } = useTripContext();
  const [manualOpen, setManualOpen] = useState(false);
  const consequence = incidents.find(
    (candidate) => candidate.routeId === Number(routeId) && candidate.incidentId === Number(incidentId),
  );
  const route = trip.routes.find((candidate) => candidate.id === Number(routeId));
  // the situation of the whole route (several incidents add up); the dialog works without it
  const { data: summaries } = useAsync(() => fetchRouteSummaries(trip.id).catch(() => [] as RouteIncidentSummary[]), [trip.id]);
  const summary = summaries?.find((candidate) => candidate.routeId === Number(routeId));

  const target = route ? stopById.get(route.toStopId) : undefined;
  const alternatives = consequence && route ? tripAlternatives(consequence, route, target, bookings) : null;
  const targetName = target?.name ?? t('alternativesSheet.yourTarget');
  const text = consequence ? consequenceText(consequence, targetName) : null;
  const tripPath = `${HOME_PATH}/${trip.id}`;
  const from = route ? stopById.get(route.fromStopId) : undefined;
  const targetStay = target
    ? bookings.find((booking) => booking.stopId === target.id && booking.type === 'ACCOMMODATION' && booking.status !== 'CANCELLED')
    : undefined;

  return (
    <Sheet
      open
      onClose={close}
      icon={<span className="va-warning-icon"><WarningOutlined /></span>}
      eyebrow={consequence ? `${incidentTypeLabel(consequence.type)} · ${categoryLabel(consequence.category)}` : t('incident.type.OTHER')}
      title={consequence ? incidentTitle(consequence) : t('incidentSheet.outdated')}
      footer={
        <button type="button" className="va-button va-button-accent va-block" onClick={close}>
          {t('alternativesSheet.backToMap')}
        </button>
      }
    >
      {consequence && route && alternatives && text ? (
        <>
          <div className={`va-impact va-impact-${(consequence.category ?? 'open').toLowerCase().replace('_', '-')}`}>
            <span className="va-eyebrow">{routeName(route, stopById)}</span>
            <span className="va-strong">{text.headline}</span>
            {text.detail && <span className="va-small">{text.detail}</span>}
            {summary && (
              <RouteSituation summary={summary} targetName={targetName}
                incidentsOnRoute={incidents.filter((i) => i.routeId === route.id).length} />
            )}
          </div>

          <section className="va-section" aria-labelledby="va-proposals-title">
            <h3 id="va-proposals-title" className="va-eyebrow">{t('alternativesSheet.solutions')}</h3>
            <ProposalsPanel
              tripId={trip.id}
              routeId={route.id}
              incidentId={consequence.incidentId}
              setting={{
                fromName: from?.name ?? t('alternativesSheet.start'),
                targetName,
                targetStayTitle: targetStay?.title ?? null,
              }}
              onChanged={reload}
              manualOpen={manualOpen}
              onToggleManual={setManualOpen}
            />
            {manualOpen && (
              <div id="va-manual-planning" className="va-column va-gap">
                <ul className="va-alternatives">
                  {alternatives.alternatives.map((alternative) => (
                    <li key={alternative.kind}>
                      <AlternativeRow alternative={alternative} tripPath={tripPath} />
                    </li>
                  ))}
                </ul>
                <p className="va-small va-muted">
                  {t('alternativesSheet.deleteHint')}
                </p>
              </div>
            )}
          </section>

          <Link to={`${tripPath}/hinweise/${route.id}/${consequence.incidentId}`} className="va-text-button">
            {t('alternativesSheet.details', { ago: formatAgo(consequence.updatedAt) })}
          </Link>
        </>
      ) : (
        <p className="va-muted">{t('incidentSheet.notAffected')}</p>
      )}
    </Sheet>
  );
}

/**
 * The whole route: what the incidents on it cost together and what is left of the slack. If the plan already arrives
 * after the arrival time of the target stop, that lateness is not the incidents' doing and is named as such.
 */
function RouteSituation({ summary, targetName, incidentsOnRoute }: {
  summary: RouteIncidentSummary;
  targetName: string;
  incidentsOnRoute: number;
}) {
  if (summary.unpassable || summary.slackMinutes === null) {
    return incidentsOnRoute > 1 ? <span className="va-small va-muted">{t('alternativesSheet.onRoute', { count: incidentsOnRoute })}</span> : null;
  }
  const incidentsDelay = minutesBetween(summary.plannedArrival, summary.expectedArrival);
  const together = incidentsOnRoute > 1 && incidentsDelay !== null
    ? t('alternativesSheet.together', {
      count: incidentsOnRoute,
      delay: incidentsDelay > 0 ? `+${formatMinutes(incidentsDelay)}` : t('alternativesSheet.noDelay'),
    })
    : null;
  if (summary.plannedTooLate) {
    const plannedLate = minutesBetween(summary.deadline, summary.plannedArrival);
    return (
      <>
        {together && <span className="va-small va-muted">{together}</span>}
        <span className="va-small va-muted">
          {plannedLate !== null
            ? t('alternativesSheet.plannedLateBy', { delay: formatMinutes(plannedLate), target: targetName })
            : t('alternativesSheet.plannedLate', { target: targetName })}
        </span>
      </>
    );
  }
  const slack =
    summary.slackMinutes < 0
      ? t('alternativesSheet.late', { delay: formatMinutes(-summary.slackMinutes) })
      : t('alternativesSheet.slack', { slack: formatMinutes(summary.slackMinutes) });
  return (
    <span className="va-small va-muted">
      {together ? t('alternativesSheet.togetherSlack', { together, slack }) : t('alternativesSheet.total', { slack })}
    </span>
  );
}

/** Whole minutes from one point in time to another, null if one is missing. */
function minutesBetween(fromIso: string | null, toIso: string | null): number | null {
  return fromIso && toIso ? Math.round((new Date(toIso).getTime() - new Date(fromIso).getTime()) / 60_000) : null;
}

function AlternativeRow({ alternative, tripPath }: { alternative: TripAlternative; tripPath: string }) {
  const icon = alternative.kind === 'ROUTE' ? <NodeIndexOutlined /> : <HomeOutlined />;
  const content = (
    <>
      <span className="va-booking-icon" aria-hidden="true">{icon}</span>
      <span className="va-column va-grow">
        <span className="va-strong">{alternative.title}</span>
        <span className="va-small va-muted">{alternative.detail}</span>
      </span>
    </>
  );
  if (!alternative.to) {
    return (
      <div className="va-alternative va-alternative-off" aria-disabled="true">
        {content}
        <span className="va-chip va-chip-off">{t('alternativesSheet.noBooking')}</span>
      </div>
    );
  }
  return (
    <Link to={`${tripPath}/${alternative.to}`} className="va-alternative">
      {content}
      <RightOutlined aria-hidden="true" />
    </Link>
  );
}
