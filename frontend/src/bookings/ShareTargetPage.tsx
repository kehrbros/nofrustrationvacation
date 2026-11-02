import { ArrowLeftOutlined } from '@ant-design/icons';
import { Alert, Spin } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';

import type { TripDetail, TripSummary } from '../trips/model';
import { localDateTimeIso, toDayInput } from '../trips/time';
import { addStop, fetchTrip, fetchTrips } from '../trips/tripsApi';
import AccommodationFields from './AccommodationFields';
import { createAccommodationBooking, extractAccommodation } from './bookingsApi';
import { emptyForm, formFromDraft, stopTimesFromForm, toInput, validateForm, type FilledForm } from './form';
import { clearShared, readShared } from './shareTarget';

const NEW_STOP = -1;

/**
 * Target of "Teilen" on Android: reads the shared confirmation, suggests trip and stop (or a new stop for the
 * accommodation) and saves after the user checked the form.
 */
export default function ShareTargetPage() {
  const navigate = useNavigate();
  const [phase, setPhase] = useState<'reading' | 'review' | 'nothing' | 'failed'>('reading');
  const [filled, setFilled] = useState<FilledForm>({ form: emptyForm(), filled: new Set(), uncertain: new Set() });
  const [trips, setTrips] = useState<TripSummary[]>([]);
  const [tripId, setTripId] = useState<number | null>(null);
  const [trip, setTrip] = useState<TripDetail | null>(null);
  const [stopId, setStopId] = useState<number>(NEW_STOP);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const form = filled.form;

  useEffect(() => {
    let active = true;
    (async () => {
      const shared = await readShared().catch(() => null);
      if (!shared) {
        if (active) setPhase('nothing');
        return;
      }
      const [draft, allTrips] = await Promise.all([
        extractAccommodation(shared.text, shared.file).catch(() => null),
        fetchTrips().catch(() => [] as TripSummary[]),
      ]);
      if (!active) return;
      const open = allTrips.filter((candidate) => candidate.phase !== 'PAST');
      setTrips(open);
      if (!draft?.found) {
        setPhase('failed');
        return;
      }
      const result = formFromDraft(draft);
      setFilled(result);
      setTripId(suggestTrip(open, result.form.checkInDate)?.id ?? null);
      setPhase('review');
    })();
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (tripId == null) {
      setTrip(null);
      return;
    }
    let active = true;
    fetchTrip(tripId).then(
      (detail) => {
        if (!active) return;
        setTrip(detail);
        setStopId(suggestStop(detail, form) ?? NEW_STOP);
      },
      () => active && setTrip(null),
    );
    return () => {
      active = false;
    };
    // the stop is suggested once per trip, not on every keystroke in the form
  }, [tripId]);

  const newStopLabel = useMemo(() => form.name.trim() || 'Unterkunft', [form.name]);

  const save = async () => {
    const problem = validateForm(form) ?? (tripId == null ? 'Bitte eine Reise wählen.' : null);
    if (problem || tripId == null) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      let target = stopId;
      if (target === NEW_STOP) {
        const times = stopTimesFromForm(form);
        const stop = await addStop(tripId, {
          name: form.name.trim(),
          latitude: form.latitude,
          longitude: form.longitude,
          arrivalAt: localDateTimeIso(times.arrival),
          departureAt: localDateTimeIso(times.departure),
        });
        target = stop.id;
      }
      const booking = await createAccommodationBooking(target, toInput(form, 'SHARE'));
      await clearShared();
      navigate(`/reisen/${tripId}/buchungen/${booking.id}`, { replace: true });
    } catch {
      setError('Die Buchung konnte nicht gespeichert werden.');
      setSaving(false);
    }
  };

  const discard = async () => {
    await clearShared();
    navigate('/reisen', { replace: true });
  };

  return (
    <div className="va-page">
      <header className="va-page-header va-page-header-start">
        <button type="button" className="va-icon-button" aria-label="Verwerfen" onClick={() => void discard()}>
          <ArrowLeftOutlined />
        </button>
        <h1 className="va-page-title va-page-title-small">Geteilte Buchung</h1>
      </header>

      {phase === 'reading' && (
        <div className="va-column va-gap">
          <Spin />
          <p className="va-muted">Bestätigung wird gelesen …</p>
        </div>
      )}
      {phase === 'nothing' && (
        <>
          <p className="va-muted">Hier ist nichts angekommen. Teile eine Buchungsbestätigung aus der Mail- oder Booking.com-App an NoFrustration.</p>
          <Link to="/reisen">Zu Meine Reisen</Link>
        </>
      )}
      {phase === 'failed' && (
        <>
          <Alert
            type="warning"
            showIcon
            message="Darin haben wir keine Unterkunftsbuchung erkannt."
            description="Öffne die Reise und füge die Buchung beim Stop hinzu, dort kannst du sie auch manuell eingeben."
          />
          <button type="button" className="va-button va-button-outline" onClick={() => void discard()}>
            Zu Meine Reisen
          </button>
        </>
      )}
      {phase === 'review' && (
        <>
          <p className="va-note">
            <span className="va-ai-mark">✦</span> aus der Bestätigung gelesen. <strong>Gelb markierte Felder bitte prüfen.</strong>
          </p>
          <div className="va-form">
            <div className="va-field">
              <label htmlFor="share-trip">Reise</label>
              {trips.length === 0 ? (
                <span className="va-muted">
                  Noch keine anstehende Reise. <Link to="/reisen/neu">Reise anlegen</Link>, danach erneut teilen.
                </span>
              ) : (
                <select id="share-trip" value={tripId ?? ''} onChange={(event) => setTripId(event.target.value ? Number(event.target.value) : null)}>
                  <option value="">Reise wählen …</option>
                  {trips.map((candidate) => (
                    <option key={candidate.id} value={candidate.id}>{candidate.name}</option>
                  ))}
                </select>
              )}
            </div>
            {trip && (
              <fieldset className="va-fieldset">
                <legend>Stop</legend>
                <label className={`va-option ${stopId === NEW_STOP ? 'va-option-selected' : ''}`}>
                  <input type="radio" name="share-stop" checked={stopId === NEW_STOP} onChange={() => setStopId(NEW_STOP)} />
                  <span>
                    <span className="va-option-title">Neuer Stop: {newStopLabel}</span>
                    <span className="va-small va-muted">Am Ende der Reise, Ankunft und Abfahrt aus der Buchung</span>
                  </span>
                </label>
                {trip.stops.map((stop) => (
                  <label key={stop.id} className={`va-option ${stopId === stop.id ? 'va-option-selected' : ''}`}>
                    <input type="radio" name="share-stop" checked={stopId === stop.id} onChange={() => setStopId(stop.id)} />
                    <span>
                      <span className="va-option-title">{stop.name}</span>
                      <span className="va-small va-muted">Stop {stop.position + 1}</span>
                    </span>
                  </label>
                ))}
              </fieldset>
            )}
          </div>
          <AccommodationFields
            form={form}
            onChange={(next) => setFilled((current) => ({ ...current, form: next }))}
            filled={filled.filled}
            uncertain={filled.uncertain}
            autoLocate
          />
          {error && <Alert type="error" showIcon message={error} />}
          <button type="button" className="va-button va-button-accent va-block" disabled={saving || tripId == null} onClick={() => void save()}>
            {saving ? 'Wird gespeichert …' : 'Buchung speichern'}
          </button>
        </>
      )}
    </div>
  );
}

/** Trip whose period contains the check-in day, else the next one. */
function suggestTrip(trips: TripSummary[], checkInDate: string): TripSummary | undefined {
  return (
    trips.find((trip) => checkInDate && toDayInput(trip.startAt) <= checkInDate && checkInDate <= toDayInput(trip.endAt)) ??
    trips[0]
  );
}

/** Stop near the accommodation (about 2 km) or arriving on the check-in day. */
function suggestStop(trip: TripDetail, form: FilledForm['form']): number | undefined {
  const near = trip.stops.find(
    (stop) =>
      stop.latitude != null && stop.longitude != null && form.latitude != null && form.longitude != null &&
      Math.abs(stop.latitude - form.latitude) < 0.02 && Math.abs(stop.longitude - form.longitude) < 0.03,
  );
  const sameDay = trip.stops.find((stop) => stop.arrivalAt && form.checkInDate && toDayInput(stop.arrivalAt) === form.checkInDate);
  return (near ?? sameDay)?.id;
}
