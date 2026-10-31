import { WarningOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { Link, useParams } from 'react-router';

import Sheet from '../components/Sheet';
import { impactLabel, incidentTitle } from '../trips/incidentText';
import { exampleSuggestions, formatDuration } from '../trips/routeExamples';
import { routeName, useTripContext } from '../trips/tripContext';
import { formatDateTime, formatTime } from '../trips/time';

/** Route between two stops: departure, incidents on it, ways to choose from (examples until routing exists). */
export default function RouteSheet() {
  const { routeId } = useParams();
  const { trip, stopById, incidentsByRoute, close } = useTripContext();
  const route = trip.routes.find((candidate) => candidate.id === Number(routeId));
  const [selected, setSelected] = useState(0);

  if (!route) {
    return (
      <Sheet open onClose={close} title="Route nicht gefunden">
        <p className="va-muted">Diese Route gibt es in der Reise nicht mehr.</p>
      </Sheet>
    );
  }
  const from = stopById.get(route.fromStopId);
  const to = stopById.get(route.toStopId);
  const incidents = incidentsByRoute.get(route.id) ?? [];
  const suggestions = exampleSuggestions(from, to);
  const departure = from?.departureAt ? new Date(from.departureAt) : null;

  return (
    <Sheet
      open
      onClose={close}
      eyebrow="Route"
      title={routeName(route, stopById)}
      footer={
        <button type="button" className="va-button va-button-accent va-block" disabled title="Folgt mit dem Routing-Dienst">
          Route übernehmen
        </button>
      }
    >
      <p className="va-muted">
        {from?.departureAt ? `Abfahrt ${formatDateTime(from.departureAt)} (aus Stop ${from.name})` : `Abfahrt offen, trage sie am Stop ${from?.name ?? ''} ein.`}
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

      <fieldset className="va-fieldset">
        <legend>Strecke wählen</legend>
        <p className="va-note">
          <strong>Beispieldaten.</strong> Echte Streckenvorschläge folgen, sobald ein Routing-Dienst angebunden ist.
        </p>
        {suggestions.length === 0 && <p className="va-muted">Für Vorschläge brauchen beide Stops einen Ort auf der Karte.</p>}
        {suggestions.map((suggestion, index) => (
          <label key={suggestion.name} className={`va-option ${selected === index ? 'va-option-selected' : ''}`}>
            <input type="radio" name="route" checked={selected === index} onChange={() => setSelected(index)} />
            <span className="va-grow">
              <span className="va-row va-space-between">
                <span className="va-option-title">{suggestion.name}</span>
                <span className="va-strong">{formatDuration(suggestion.minutes)}</span>
              </span>
              <span className="va-muted">
                {suggestion.kilometers} km
                {departure && ` · Ankunft ca. ${formatTime(new Date(departure.getTime() + suggestion.minutes * 60000).toISOString())}`}
              </span>
            </span>
          </label>
        ))}
      </fieldset>
      <span className="va-link-disabled" aria-disabled="true">
        + Zwischenziel hinzufügen <span className="va-muted">(folgt)</span>
      </span>
    </Sheet>
  );
}
