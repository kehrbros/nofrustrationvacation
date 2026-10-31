import { EnvironmentOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useEffect, useState, type FormEvent } from 'react';

import Sheet from '../components/Sheet';
import { searchPlaces, type Place } from '../map/geocoding';
import { useTripContext } from '../trips/tripContext';
import { localDateTimeIso } from '../trips/time';
import { addStop } from '../trips/tripsApi';

/** "Stop hinzufügen": place search, arrival and departure; the stop is appended to the trip. */
export default function StopSheet() {
  const { trip, reload, close } = useTripContext();
  const [query, setQuery] = useState('');
  const [places, setPlaces] = useState<Place[]>([]);
  const [place, setPlace] = useState<Place | null>(null);
  const [searchFailed, setSearchFailed] = useState(false);
  const [arrival, setArrival] = useState('');
  const [departure, setDeparture] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const previous = trip.stops.at(-1);

  useEffect(() => {
    if (place || query.trim().length < 3) {
      setPlaces([]);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      searchPlaces(query.trim(), controller.signal).then(
        (found) => {
          setPlaces(found);
          setSearchFailed(false);
        },
        (failure: unknown) => {
          if (!(failure instanceof DOMException && failure.name === 'AbortError')) {
            setSearchFailed(true);
          }
        },
      );
    }, 300);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [query, place]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const name = (place?.name ?? query).trim();
    const arrivalAt = localDateTimeIso(arrival);
    const departureAt = localDateTimeIso(departure);
    if (!name) {
      setError('Bitte einen Ort angeben.');
      return;
    }
    if (arrivalAt && departureAt && new Date(departureAt) < new Date(arrivalAt)) {
      setError('Die Abfahrt liegt vor der Ankunft.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await addStop(trip.id, {
        name,
        latitude: place?.latitude ?? null,
        longitude: place?.longitude ?? null,
        arrivalAt,
        departureAt,
      });
      reload();
      close();
    } catch {
      setError('Der Stop konnte nicht gespeichert werden.');
      setSaving(false);
    }
  };

  return (
    <Sheet
      open
      onClose={close}
      title="Stop hinzufügen"
      height="90vh"
      footer={
        <button type="submit" form="stop-form" className="va-button va-button-accent va-block" disabled={saving}>
          {saving ? 'Wird gespeichert …' : 'Stop speichern'}
        </button>
      }
    >
      <form id="stop-form" className="va-form" onSubmit={(event) => void submit(event)} noValidate>
        <div className="va-field">
          <label htmlFor="stop-place">Ort</label>
          <input
            id="stop-place"
            type="search"
            autoComplete="off"
            value={place ? place.name : query}
            aria-describedby="stop-place-hint"
            onChange={(event) => {
              setPlace(null);
              setQuery(event.target.value);
            }}
          />
          <span id="stop-place-hint" className="va-small va-muted">
            {place ? `${place.detail || 'Ort gewählt'} · auf der Karte` : 'Mindestens 3 Zeichen, dann einen Vorschlag wählen.'}
          </span>
          {places.length > 0 && (
            <ul className="va-suggestions" aria-label="Vorschläge">
              {places.map((candidate) => (
                <li key={`${candidate.latitude},${candidate.longitude}`}>
                  <button type="button" onClick={() => setPlace(candidate)}>
                    <EnvironmentOutlined aria-hidden="true" />
                    <span className="va-column">
                      <span className="va-strong">{candidate.name}</span>
                      <span className="va-small va-muted">{candidate.detail}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {searchFailed && <span className="va-small va-muted">Die Ortssuche ist gerade nicht erreichbar, der Stop wird ohne Kartenposition gespeichert.</span>}
        </div>

        <div className="va-field">
          <span className="va-label">Reihenfolge</span>
          <span className="va-muted">
            {previous ? `Am Ende der Reise, nach ${previous.name} (Stop ${trip.stops.length})` : 'Erster Stop der Reise'}
          </span>
        </div>

        <div className="va-field-row">
          <div className="va-field">
            <label htmlFor="stop-arrival">Ankunft</label>
            <input id="stop-arrival" type="datetime-local" value={arrival} onChange={(event) => setArrival(event.target.value)} />
          </div>
          <div className="va-field">
            <label htmlFor="stop-departure">Abfahrt</label>
            <input id="stop-departure" type="datetime-local" value={departure} min={arrival || undefined} onChange={(event) => setDeparture(event.target.value)} />
          </div>
        </div>

        <span className="va-link-disabled" aria-disabled="true">
          + Buchung hinzufügen (Unterkunft, Fähre, …) <span className="va-muted">(folgt)</span>
        </span>
        {error && <Alert type="error" showIcon message={error} />}
      </form>
    </Sheet>
  );
}
