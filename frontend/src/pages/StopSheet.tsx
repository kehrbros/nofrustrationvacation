import { CloseOutlined, EnvironmentOutlined, HomeOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';

import AddBookingSheet from '../bookings/AddBookingSheet';
import { createAccommodationBooking } from '../bookings/bookingsApi';
import { stopTimesFromForm, toInput, type AccommodationForm } from '../bookings/form';
import type { BookingSource } from '../bookings/model';
import { stayDefaults } from '../bookings/stayDefaults';
import Sheet from '../components/Sheet';
import { searchPlaces, type Place } from '../map/geocoding';
import { useTripContext } from '../trips/tripContext';
import { localDateTimeIso } from '../trips/time';
import { addStop } from '../trips/tripsApi';

/**
 * "Stop hinzufügen": place search, arrival and departure; the stop is appended to the trip. An accommodation booking
 * entered here fills place and times if still empty and is saved with the stop.
 */
export default function StopSheet() {
  const { trip, bookings, reload, close } = useTripContext();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [places, setPlaces] = useState<Place[]>([]);
  const [place, setPlace] = useState<Place | null>(null);
  const [searchFailed, setSearchFailed] = useState(false);
  const [arrival, setArrival] = useState('');
  const [departure, setDeparture] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [addingBooking, setAddingBooking] = useState(false);
  const [booking, setBooking] = useState<{ form: AccommodationForm; source: BookingSource } | null>(null);
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
      const updated = await addStop(trip.id, {
        name,
        latitude: place?.latitude ?? null,
        longitude: place?.longitude ?? null,
        arrivalAt,
        departureAt,
      });
      // appended: the new stop is the last one, the new route leads to it
      const stop = updated.stops.at(-1)!;
      const newRoute = updated.routes.find((route) => route.toStopId === stop.id);
      if (booking) {
        try {
          await createAccommodationBooking(stop.id, toInput(booking.form, booking.source));
        } catch {
          // the stop exists: show it, the booking can be added again from the stop list
          reload();
          setSaving(false);
          setError('Der Stop ist gespeichert, die Buchung nicht. Füge sie in der Liste beim Stop erneut hinzu.');
          return;
        }
      }
      reload();
      // choose the way to the new stop right away, if both stops are on the map
      const previousStop = newRoute ? updated.stops.find((candidate) => candidate.id === newRoute.fromStopId) : undefined;
      if (newRoute && stop.latitude != null && previousStop?.latitude != null) {
        navigate(`/reisen/${trip.id}/routen/${newRoute.id}?neu=1`);
      } else {
        close();
      }
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

        {booking ? (
          <div className="va-booking-row va-booking-draft">
            <span className="va-booking-icon" aria-hidden="true"><HomeOutlined /></span>
            <span className="va-column va-grow">
              <span className="va-strong">{booking.form.name}</span>
              <span className="va-small va-muted">
                {booking.form.checkInDate} bis {booking.form.checkOutDate} · wird mit dem Stop gespeichert
              </span>
            </span>
            <button type="button" className="va-icon-button va-plain" aria-label="Buchung entfernen" onClick={() => setBooking(null)}>
              <CloseOutlined />
            </button>
          </div>
        ) : (
          <button type="button" className="va-text-button va-add-booking" onClick={() => setAddingBooking(true)}>
            + Buchung hinzufügen (Unterkunft)
          </button>
        )}
        {error && <Alert type="error" showIcon message={error} />}
      </form>
      {addingBooking && (
        <AddBookingSheet
          context="Neuer Stop"
          submitLabel="Übernehmen"
          defaults={stayDefaults(trip, { arrivalAt: localDateTimeIso(arrival), departureAt: localDateTimeIso(departure) }, bookings)}
          near={place ? { latitude: place.latitude, longitude: place.longitude } : null}
          onClose={() => setAddingBooking(false)}
          onSubmit={async (form, source) => {
            setBooking({ form, source });
            // the stay fills what the user has not entered yet
            if (!place && !query.trim()) {
              if (form.latitude != null && form.longitude != null) {
                setPlace({ name: form.name, detail: form.placeDetail, latitude: form.latitude, longitude: form.longitude });
              } else {
                setQuery(form.name);
              }
            }
            const times = stopTimesFromForm(form);
            if (!arrival) {
              setArrival(times.arrival);
            }
            if (!departure) {
              setDeparture(times.departure);
            }
            setAddingBooking(false);
          }}
        />
      )}
    </Sheet>
  );
}
