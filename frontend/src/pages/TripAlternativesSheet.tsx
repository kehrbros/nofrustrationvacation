import { HomeOutlined, NodeIndexOutlined, RightOutlined, WarningOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { Link, useParams } from 'react-router';

import { useAsync } from '../api/useAsync';
import Sheet from '../components/Sheet';
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
  const text = consequence ? consequenceText(consequence, target?.name ?? 'dein Ziel') : null;
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
      eyebrow={consequence ? `${incidentTypeLabel(consequence.type)} · ${categoryLabel(consequence.category)}` : 'Hinweis'}
      title={consequence ? incidentTitle(consequence) : 'Hinweis nicht mehr aktuell'}
      footer={
        <button type="button" className="va-button va-button-accent va-block" onClick={close}>
          Zurück zur Karte
        </button>
      }
    >
      {consequence && route && alternatives && text ? (
        <>
          <div className={`va-impact va-impact-${(consequence.category ?? 'open').toLowerCase().replace('_', '-')}`}>
            <span className="va-eyebrow">{routeName(route, stopById)}</span>
            <span className="va-strong">{text.headline}</span>
            {text.detail && <span className="va-small">{text.detail}</span>}
            {summary && <RouteSituation summary={summary} incidentsOnRoute={incidents.filter((i) => i.routeId === route.id).length} />}
          </div>

          <section className="va-section" aria-labelledby="va-proposals-title">
            <h3 id="va-proposals-title" className="va-eyebrow">Lösungen</h3>
            <ProposalsPanel
              tripId={trip.id}
              routeId={route.id}
              incidentId={consequence.incidentId}
              setting={{
                fromName: from?.name ?? 'Start',
                targetName: target?.name ?? 'dein Ziel',
                targetStayTitle: targetStay?.title ?? null,
              }}
              onChanged={reload}
              onPlanMyself={() => setManualOpen(true)}
            />
          </section>

          <section className="va-section" aria-labelledby="va-alternatives-title">
            <button type="button" className="va-text-button va-eyebrow" id="va-alternatives-title"
              aria-expanded={manualOpen} onClick={() => setManualOpen((value) => !value)}>
              Selbst umplanen {manualOpen ? '↑' : '↓'}
            </button>
            {manualOpen && (
              <>
                <ul className="va-alternatives">
                  {alternatives.alternatives.map((alternative) => (
                    <li key={alternative.kind}>
                      <AlternativeRow alternative={alternative} tripPath={tripPath} />
                    </li>
                  ))}
                </ul>
                <p className="va-small va-muted">
                  Löschst du dabei einen Stop, kommen seine Buchungen auf deine To-do-Liste, damit du sie stornieren kannst.
                </p>
              </>
            )}
          </section>


          <Link to={`${tripPath}/hinweise/${route.id}/${consequence.incidentId}`} className="va-text-button">
            Details zum Hinweis · aktualisiert {formatAgo(consequence.updatedAt)}
          </Link>
        </>
      ) : (
        <p className="va-muted">Dieser Hinweis betrifft deine Reise nicht mehr.</p>
      )}
    </Sheet>
  );
}

/** Slack of the whole route when more than this incident is on it, or when it is tight. */
function RouteSituation({ summary, incidentsOnRoute }: { summary: RouteIncidentSummary; incidentsOnRoute: number }) {
  if (summary.unpassable || summary.slackMinutes === null) {
    return incidentsOnRoute > 1 ? <span className="va-small va-muted">{incidentsOnRoute} Hinweise auf dieser Route</span> : null;
  }
  const slack =
    summary.slackMinutes < 0
      ? `${formatMinutes(-summary.slackMinutes)} zu spät`
      : `${formatMinutes(summary.slackMinutes)} Puffer`;
  return (
    <span className="va-small va-muted">
      {incidentsOnRoute > 1 ? `Alle ${incidentsOnRoute} Hinweise zusammen: ${slack}` : `Insgesamt: ${slack}`}
    </span>
  );
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
        <span className="va-chip va-chip-off">Keine Buchung</span>
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
