import { ArrowLeftOutlined } from '@ant-design/icons';
import { Alert, Spin } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';

import type { TripDetail, TripSummary } from '../trips/model';
import { localDateTimeIso, toDayInput } from '../trips/time';
import { addStop, fetchTrip, fetchTrips } from '../trips/tripsApi';
import { t } from '../i18n';
import Rich from '../i18n/Rich';
import AccommodationFields from './AccommodationFields';
import { createAccommodationBooking, isExtractionAvailable } from './bookingsApi';
import ConfirmationInput from './ConfirmationInput';
import { emptyForm, stopTimesFromForm, toInput, validateForm, withStayDefaults, type FilledForm } from './form';
import type { BookingSource } from './model';
import { readConfirmation } from './readConfirmation';
import { stopPoint } from './stayDefaults';
import { clearShared, readShared, type SharedContent } from './shareTarget';

const NEW_STOP = -1;

/**
 * Target of "Teilen" on Android: the same way as "Bestätigung einfügen" (AddBookingSheet). The shared content can be
 * completed with pasted text or a file, the assistant reads all of it, then the page suggests trip and stop (or a new
 * stop for the accommodation) and saves after the user checked the form.
 */
export default function ShareTargetPage() {
  const navigate = useNavigate();
  const [phase, setPhase] = useState<'loading' | 'paste' | 'review' | 'nothing'>('loading');
  const [shared, setShared] = useState<SharedContent | null>(null);
  const [text, setText] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [available, setAvailable] = useState<boolean | null>(null);
  const [reading, setReading] = useState(false);
  const [readError, setReadError] = useState<string | null>(null);
  const [source, setSource] = useState<BookingSource>('SHARE');
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
    void isExtractionAvailable().then((value) => active && setAvailable(value));
    (async () => {
      const [content, allTrips] = await Promise.all([
        readShared().catch(() => null),
        fetchTrips().catch(() => [] as TripSummary[]),
      ]);
      if (!active) return;
      setTrips(allTrips.filter((candidate) => candidate.phase !== 'PAST'));
      setShared(content);
      setPhase(content ? 'paste' : 'nothing');
    })();
    return () => {
      active = false;
    };
  }, []);

  const showReview = (result: FilledForm, how: BookingSource) => {
    setFilled(result);
    setSource(how);
    setTripId(suggestTrip(trips, result.form.checkInDate)?.id ?? null);
    setPhase('review');
  };

  const read = async () => {
    setReading(true);
    setReadError(null);
    const result = await readConfirmation(shared, text, file);
    setReading(false);
    if (result.ok) {
      showReview(result.filled, shared ? 'SHARE' : 'DOCUMENT');
    } else {
      setReadError(result.message);
    }
  };

  const manual = () => showReview({ form: emptyForm(), filled: new Set(), uncertain: new Set() }, 'MANUAL');

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
        // the travelers of the trip as guests if the confirmation did not name them
        setFilled((current) => ({ ...current, form: withStayDefaults(current.form, { guests: detail.travelers }) }));
      },
      () => active && setTrip(null),
    );
    return () => {
      active = false;
    };
    // the stop is suggested once per trip, not on every keystroke in the form
  }, [tripId]);

  const newStopLabel = useMemo(() => form.name.trim() || t('addBooking.accommodation'), [form.name]);

  const save = async () => {
    const problem = validateForm(form) ?? (tripId == null ? t('shareTarget.chooseTrip') : null);
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
      const booking = await createAccommodationBooking(target, toInput(form, source));
      await clearShared();
      navigate(`/reisen/${tripId}/buchungen/${booking.id}`, { replace: true });
    } catch {
      setError(t('addBooking.saveFailed'));
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
        <button type="button" className="va-icon-button" aria-label={t('shareTarget.discard')} onClick={() => void discard()}>
          <ArrowLeftOutlined />
        </button>
        <h1 className="va-page-title va-page-title-small">{t('shareTarget.title')}</h1>
      </header>

      {phase === 'loading' && (
        <div className="va-column va-gap">
          <Spin />
        </div>
      )}
      {phase === 'nothing' && (
        <>
          <p className="va-muted">{t('shareTarget.nothing')}</p>
          <Link to="/reisen">{t('shareTarget.toTrips')}</Link>
        </>
      )}
      {phase === 'paste' && (
        <>
          <ConfirmationInput
            shared={shared}
            onSharedChange={setShared}
            text={text}
            onTextChange={setText}
            file={file}
            onFileChange={setFile}
            available={available}
            error={readError}
            onError={setReadError}
            onManual={manual}
          />
          <button
            type="button"
            className="va-button va-button-accent va-block"
            disabled={reading || available === false || (!text.trim() && !file && !shared)}
            onClick={() => void read()}
          >
            {reading ? t('addBooking.reading') : t('addBooking.read')}
          </button>
        </>
      )}
      {phase === 'review' && (
        <>
          <button type="button" className="va-text-button va-back" onClick={() => setPhase('paste')}>
            {t('common.back')}
          </button>
          {source !== 'MANUAL' && (
            <p className="va-note">
              <Rich text={t('addBooking.fromConfirmation')} />
            </p>
          )}
          <div className="va-form">
            <div className="va-field">
              <label htmlFor="share-trip">{t('shareTarget.trip')}</label>
              {trips.length === 0 ? (
                <span className="va-muted">
                  {t('shareTarget.noTripBefore')}<Link to="/reisen/neu">{t('newTrip.create')}</Link>{t('shareTarget.noTripAfter')}
                </span>
              ) : (
                <select id="share-trip" value={tripId ?? ''} onChange={(event) => setTripId(event.target.value ? Number(event.target.value) : null)}>
                  <option value="">{t('shareTarget.tripPlaceholder')}</option>
                  {trips.map((candidate) => (
                    <option key={candidate.id} value={candidate.id}>{candidate.name}</option>
                  ))}
                </select>
              )}
            </div>
            {trip && (
              <fieldset className="va-fieldset">
                <legend>{t('shareTarget.stop')}</legend>
                <label className={`va-option ${stopId === NEW_STOP ? 'va-option-selected' : ''}`}>
                  <input type="radio" name="share-stop" checked={stopId === NEW_STOP} onChange={() => setStopId(NEW_STOP)} />
                  <span>
                    <span className="va-option-title">{t('shareTarget.newStop', { name: newStopLabel })}</span>
                    <span className="va-small va-muted">{t('shareTarget.newStopHint')}</span>
                  </span>
                </label>
                {trip.stops.map((stop) => (
                  <label key={stop.id} className={`va-option ${stopId === stop.id ? 'va-option-selected' : ''}`}>
                    <input type="radio" name="share-stop" checked={stopId === stop.id} onChange={() => setStopId(stop.id)} />
                    <span>
                      <span className="va-option-title">{stop.name}</span>
                      <span className="va-small va-muted">{t('shareTarget.stopNumber', { position: stop.position + 1 })}</span>
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
            autoLocate={source !== 'MANUAL'}
            near={stopPoint(trip?.stops.find((stop) => stop.id === stopId))}
          />
          {error && <Alert type="error" showIcon message={error} />}
          <button type="button" className="va-button va-button-accent va-block" disabled={saving || tripId == null} onClick={() => void save()}>
            {saving ? t('common.saving') : t('addBooking.save')}
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
