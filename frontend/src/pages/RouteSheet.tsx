import { WarningOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';

import Sheet from '../components/Sheet';
import { impactLabel, incidentTitle } from '../trips/incidentText';
import type { PlanOutcome, RouteOption, RouteSuggestions } from '../trips/model';
import { formatDistance, formatDuration, optionTitle, slowerBy, substituteDeparture } from '../trips/routeOptionText';
import { chooseRoute, fetchSuggestions, fetchUserWaypointCount } from '../trips/routesApi';
import { routeName, useTripContext } from '../trips/tripContext';
import { formatDateTime, formatTime } from '../trips/time';
import { TripsApiError } from '../trips/tripsApi';

type Loaded = { state: 'loading' } | { state: 'failed'; unavailable: boolean } | { state: 'ready'; result: RouteSuggestions };

/**
 * Route between two stops: up to three ways computed now (not stored), drawn on the map; the chosen one is stored as
 * waypoints of the route, see Docs/recommendations.md#frontend. Opened right after adding a stop with ?neu=1.
 */
export default function RouteSheet() {
  const { routeId } = useParams();
  const [searchParams] = useSearchParams();
  const isNew = searchParams.get('neu') === '1';
  const { trip, stopById, incidentsByRoute, routeLines, setRoutePreview, reload, close } = useTripContext();
  const route = trip.routes.find((candidate) => candidate.id === Number(routeId));
  const from = route ? stopById.get(route.fromStopId) : undefined;
  const to = route ? stopById.get(route.toStopId) : undefined;
  // without a departure at the start stop the backend computes from a substitute, arrival times are then not shown
  const departure = substituteDeparture(trip, from);
  const [includeWaypoints, setIncludeWaypoints] = useState(false);
  const [hasWaypoints, setHasWaypoints] = useState(false);
  const [loaded, setLoaded] = useState<Loaded>({ state: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const [selected, setSelected] = useState(0);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // stored, but planning failed: the sheet stays open and says why the route is not drawn yet
  const [notPlanned, setNotPlanned] = useState<PlanOutcome | null>(null);

  useEffect(() => {
    if (!route) {
      return;
    }
    let active = true;
    setLoaded({ state: 'loading' });
    fetchSuggestions(route.id, { includeWaypoints, departureAt: departure }).then(
      (result) => {
        if (active) {
          setLoaded({ state: 'ready', result });
          setSelected(0);
        }
      },
      (failure: unknown) => {
        if (active) {
          setLoaded({ state: 'failed', unavailable: failure instanceof TripsApiError && failure.status === 503 });
        }
      },
    );
    return () => {
      active = false;
    };
  }, [route?.id, includeWaypoints, departure, attempt]); // eslint-disable-line react-hooks/exhaustive-deps

  // the switch "Zwischenziele berücksichtigen" only matters if the user set waypoints of their own
  useEffect(() => {
    if (!route) {
      return;
    }
    let active = true;
    fetchUserWaypointCount(trip.id, route.id).then(
      (count) => active && setHasWaypoints(count > 0),
      () => active && setHasWaypoints(false),
    );
    return () => {
      active = false;
    };
  }, [trip.id, route?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const options = useMemo(() => (loaded.state === 'ready' ? loaded.result.options : []), [loaded]);
  const lines = useMemo(() => options.map((option) => option.geometry), [options]);

  // draw the options on the map while the sheet is open
  useEffect(() => {
    if (!route || lines.length === 0) {
      setRoutePreview(null);
      return;
    }
    setRoutePreview({ routeId: route.id, lines, selected, onSelect: setSelected });
  }, [route?.id, lines, selected, setRoutePreview]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => () => setRoutePreview(null), [setRoutePreview]);

  if (!route) {
    return (
      <Sheet open onClose={close} title={isNew ? 'Route wird geladen …' : 'Route nicht gefunden'}>
        {!isNew && <p className="va-muted">Diese Route gibt es in der Reise nicht mehr.</p>}
      </Sheet>
    );
  }
  const incidents = incidentsByRoute.get(route.id) ?? [];
  const incidentTitles = new Map(incidents.map((incident) => [incident.incidentId, incidentTitle(incident)]));
  const planned = routeLines.get(route.id);
  const chosen = options[selected];

  const save = async () => {
    if (!chosen) {
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const outcome = await chooseRoute(route.id, chosen.waypoints);
      reload();
      if (outcome === null || outcome === 'PLANNED') {
        close();
      } else {
        setNotPlanned(outcome);
        setSaving(false);
      }
    } catch {
      setError('Die Strecke konnte nicht gespeichert werden.');
      setSaving(false);
    }
  };

  return (
    <Sheet
      open
      onClose={close}
      mask={false}
      height="60vh"
      eyebrow="Route"
      title={routeName(route, stopById)}
      footer={
        <div className="va-column va-gap">
          {notPlanned ? (
            <button type="button" className="va-button va-button-accent va-block" onClick={close}>
              Schließen
            </button>
          ) : (
            <button type="button" className="va-button va-button-accent va-block" disabled={!chosen || saving} onClick={save}>
              {saving ? 'Wird gespeichert …' : options.length === 1 ? 'Strecke bestätigen' : 'Diese Strecke nehmen'}
            </button>
          )}
          {isNew && !notPlanned && (
            <button type="button" className="va-text-button" onClick={close}>
              Später
            </button>
          )}
        </div>
      }
    >
      <p className="va-muted">
        {from?.departureAt
          ? `Abfahrt ${formatDateTime(from.departureAt)} (aus Stop ${from.name})`
          : `Abfahrt offen, trage sie am Stop ${from?.name ?? ''} ein. Bis dahin zeigen wir Fahrzeit und Strecke.`}
      </p>

      {incidents.length > 0 && (
        <ul className="va-plain-list">
          {incidents.map((incident) => (
            <li key={incident.incidentId}>
              <Link to={`../hinweise/${route.id}/${incident.incidentId}`} relative="path" className="va-incident-row">
                <WarningOutlined aria-hidden="true" />
                <span className="va-grow">{incidentTitle(incident)}</span>
                <span className="va-chip va-chip-soft">{impactLabel(incident.impact)}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}

      {hasWaypoints && (
        <label className="va-row va-gap">
          <input type="checkbox" checked={includeWaypoints} onChange={(event) => setIncludeWaypoints(event.target.checked)} />
          <span>Zwischenziele berücksichtigen</span>
        </label>
      )}

      <fieldset className="va-fieldset" aria-busy={loaded.state === 'loading'}>
        <legend>Strecke wählen</legend>
        {loaded.state === 'loading' && (
          <>
            <p className="va-muted">Suche Strecken …</p>
            {[0, 1, 2].map((index) => (
              <div key={index} className="va-option va-option-skeleton" aria-hidden="true" />
            ))}
          </>
        )}
        {loaded.state === 'failed' && (
          <Alert
            type="warning"
            showIcon
            message={loaded.unavailable ? 'Streckensuche gerade nicht verfügbar.' : 'Die Strecken konnten nicht geladen werden.'}
            action={
              <button type="button" className="va-text-button" onClick={() => setAttempt((value) => value + 1)}>
                Erneut versuchen
              </button>
            }
          />
        )}
        {loaded.state === 'ready' && (
          <ProblemText problem={loaded.result.problem} hasOptions={options.length > 0} stops={[from?.name, to?.name]} />
        )}
        {options.length === 1 && <p className="va-muted">Für diese Strecke gibt es keine sinnvolle Alternative.</p>}
        {options.map((option, index) => (
          <OptionCard
            key={option.rank}
            option={option}
            fastest={options[0]}
            selected={selected === index}
            single={options.length === 1}
            showArrival={departure === null}
            incidentTitles={incidentTitles}
            onSelect={() => setSelected(index)}
          />
        ))}
        {planned && options.length > 0 && !options.some((option) => option.labels.includes('CURRENT')) && (
          <p className="va-small va-muted">
            Bisher geplant: {planned.via ? `über ${planned.via}, ` : ''}
            {formatDuration(planned.durationSeconds)}, {formatDistance(planned.distanceMeters)}
          </p>
        )}
      </fieldset>
      {hasWaypoints && !includeWaypoints && chosen && <p className="va-note">Deine Zwischenziele werden entfernt.</p>}
      {notPlanned && <Alert type="info" showIcon message="Strecke gespeichert, aber noch nicht geplant" description={notPlannedText(notPlanned, from?.name)} />}
      {error && <Alert type="error" showIcon message={error} />}
    </Sheet>
  );
}

/** Why a chosen route is stored but not planned (no line on the map yet), see Docs/recommendations.md#auswahl-speichern. */
function notPlannedText(outcome: PlanOutcome, fromName: string | undefined): string {
  switch (outcome) {
    case 'MISSING_DEPARTURE':
      return `Trag am Stop ${fromName ?? ''} eine Abfahrt ein. Dann wird die Strecke über deine Wahl geplant und auf der Karte gezeigt.`;
    case 'MISSING_COORDINATES':
      return 'Beide Stops brauchen einen Ort auf der Karte.';
    case 'ROUTING_UNAVAILABLE':
      return 'Die Streckensuche ist gerade nicht verfügbar. Versuch es später noch einmal.';
    default:
      return 'Für diese Wahl wurde keine Straßenverbindung gefunden. Wähl eine andere Strecke.';
  }
}

function ProblemText({
  problem,
  hasOptions,
  stops,
}: {
  problem: RouteSuggestions['problem'];
  hasOptions: boolean;
  stops: (string | undefined)[];
}) {
  if (problem === 'MISSING_COORDINATES') {
    return <p className="va-muted">Beide Stops brauchen einen Ort auf der Karte ({stops.filter(Boolean).join(', ')}).</p>;
  }
  if (problem === 'MISSING_DEPARTURE') {
    return <p className="va-muted">Für Vorschläge braucht der Start-Stop eine Ankunft oder Abfahrt.</p>;
  }
  if (problem === 'NO_ROUTE' || !hasOptions) {
    return <p className="va-muted">Keine Straßenverbindung gefunden, z. B. bei Fähren.</p>;
  }
  return null;
}

function OptionCard({
  option,
  fastest,
  selected,
  single,
  showArrival,
  incidentTitles,
  onSelect,
}: {
  option: RouteOption;
  fastest: RouteOption;
  selected: boolean;
  single: boolean;
  showArrival: boolean;
  incidentTitles: Map<number, string>;
  onSelect: () => void;
}) {
  const slower = slowerBy(option, fastest);
  const current = option.labels.includes('CURRENT');
  const avoids = option.labels.includes('AVOIDS_INCIDENT');
  return (
    <label className={`va-option ${selected ? 'va-option-selected' : ''}`}>
      {!single && <input type="radio" name="route" checked={selected} onChange={onSelect} />}
      <span className="va-grow">
        <span className="va-row va-space-between">
          <span className="va-option-title">{optionTitle(option)}</span>
          <span className="va-strong">{formatDuration(option.durationSeconds + option.delaySeconds)}</span>
        </span>
        <span className="va-row va-space-between">
          <span className="va-muted">
            {formatDistance(option.distanceMeters)}
            {showArrival && ` · Ankunft ca. ${formatTime(option.arrivalAt)}`}
          </span>
          {slower && <span className="va-small va-muted">{slower}</span>}
        </span>
        {(current || avoids) && (
          <span className="va-row va-gap">
            {current && <span className="va-chip va-chip-soft">Aktuell geplant</span>}
            {avoids && <span className="va-chip va-chip-soft">Umfährt Hinweis</span>}
          </span>
        )}
        {option.conflicts.map((conflict) => (
          <span key={conflict.incidentId} className="va-row va-gap va-small">
            <WarningOutlined aria-hidden="true" />
            <span>
              {incidentTitles.get(conflict.incidentId) ?? 'Hinweis auf der Strecke'}
              {conflict.impact === 'BLOCKED'
                ? ' · gesperrt'
                : conflict.delaySeconds
                  ? ` · +${formatDuration(conflict.delaySeconds)}`
                  : ''}
            </span>
          </span>
        ))}
      </span>
    </label>
  );
}
