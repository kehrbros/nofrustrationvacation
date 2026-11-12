import { CloseOutlined, EnvironmentOutlined, HomeOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router';

import AddBookingSheet from '../bookings/AddBookingSheet';
import { createAccommodationBooking } from '../bookings/bookingsApi';
import { stopTimesFromForm, toInput, type AccommodationForm } from '../bookings/form';
import type { BookingSource } from '../bookings/model';
import { stayDefaults } from '../bookings/stayDefaults';
import Field, { RequiredNote } from '../components/form/Field';
import { failedField, useFieldErrors, type FieldErrors } from '../components/form/validation';
import KiwiLoader from '../components/KiwiLoader';
import Sheet from '../components/Sheet';
import { t } from '../i18n';
import { searchPlaces, type Place } from '../map/geocoding';
import type { NewStop, Stop } from '../trips/model';
import StayConflictDialog from '../trips/StayConflictDialog';
import { outsideBookingLabel, stopBookingsConflict, stopDaysLabel, type StopBookingsConflict } from '../trips/stayConflict';
import { useTripContext } from '../trips/tripContext';
import { localDateTimeIso, toDateTimeInput } from '../trips/time';
import { addStop, TripsApiError, updateStop } from '../trips/tripsApi';

type StopField = 'name' | 'place' | 'arrival' | 'departure';
const FIELDS: StopField[] = ['name', 'place', 'arrival', 'departure'];

/** What is wrong with the stop, per field (same rules as TripLogic). */
function stopErrors(place: Place | null, arrival: string, departure: string): FieldErrors<StopField> {
  const errors: FieldErrors<StopField> = {};
  // stops need a position in New Zealand, only places from the suggestions have one
  if (!place) {
    errors.place = t('stop.choosePlace');
  }
  const arrivalAt = localDateTimeIso(arrival);
  const departureAt = localDateTimeIso(departure);
  if (arrivalAt && departureAt && new Date(departureAt) < new Date(arrivalAt)) {
    errors.departure = t('stop.departureBeforeArrival');
  }
  return errors;
}

/**
 * "Stop hinzufügen": place search, arrival and departure; the stop is appended to the trip. An accommodation booking
 * entered here fills place and times if still empty and is saved with the stop.
 *
 * With a stop id in the path "Stop bearbeiten": name, place and times of that stop. Bookings at the stop must fit its
 * new days, otherwise a dialog offers to extend the stop to them or shorten them (Docs/domain.md#bookings).
 */
export default function StopSheet() {
  const { trip, stopById, close } = useTripContext();
  const { stopId } = useParams();
  if (stopId === undefined) {
    return <StopForm />;
  }
  const stop = stopById.get(Number(stopId));
  if (!stop) {
    return (
      <Sheet open onClose={close} title={t('stop.notFound')}>
        <p className="va-muted">{t('stop.gone')}</p>
      </Sheet>
    );
  }
  // a new form per stop, its fields start with the stop's values
  return <StopForm key={`${trip.id}-${stop.id}`} edited={stop} />;
}

function StopForm({ edited }: { edited?: Stop }) {
  const { trip, bookings, reload, close } = useTripContext();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [places, setPlaces] = useState<Place[]>([]);
  const [place, setPlace] = useState<Place | null>(
    edited?.latitude != null && edited.longitude != null
      ? { name: edited.name, detail: '', latitude: edited.latitude, longitude: edited.longitude }
      : null,
  );
  const [name, setName] = useState(edited?.name ?? '');
  const [searchFailed, setSearchFailed] = useState(false);
  const [noMatch, setNoMatch] = useState(false);
  const [arrival, setArrival] = useState(toDateTimeInput(edited?.arrivalAt ?? null));
  const [departure, setDeparture] = useState(toDateTimeInput(edited?.departureAt ?? null));
  const [conflict, setConflict] = useState<{ stop: NewStop; conflict: StopBookingsConflict } | null>(null);
  const [conflictError, setConflictError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [addingBooking, setAddingBooking] = useState(false);
  const [booking, setBooking] = useState<{ form: AccommodationForm; source: BookingSource } | null>(null);
  const previous = trip.stops.at(-1);
  const validation = useFieldErrors(stopErrors(place, arrival, departure), { name, place, arrival, departure });
  const { errors, touch } = validation;
  /** A refused stop: marks the field the backend named, else shows the message below the form. */
  const refused = (failure: unknown) => {
    if (failure instanceof TripsApiError && failure.code === 'LOCATION_OUTSIDE_REGIONS') {
      validation.refuse('place', t('stop.outsideRegions'));
      return;
    }
    const field = failedField(failure, FIELDS, {
      latitude: 'place', longitude: 'place', name: edited ? 'name' : 'place', arrivalAt: 'arrival', departureAt: 'departure',
    });
    if (field) {
      validation.refuse(field);
      return;
    }
    setError(t('stop.saveFailed'));
  };

  useEffect(() => {
    if (place || query.trim().length < 3) {
      setPlaces([]);
      setNoMatch(false);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      searchPlaces(query.trim(), controller.signal).then(
        (found) => {
          setPlaces(found);
          setNoMatch(found.length === 0);
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
    const arrivalAt = localDateTimeIso(arrival);
    const departureAt = localDateTimeIso(departure);
    if (!validation.check() || !place) {
      return;
    }
    if (edited) {
      setError(null);
      await saveChanges({
        name: name.trim() || place.name.trim(),
        latitude: place.latitude,
        longitude: place.longitude,
        arrivalAt,
        departureAt,
      });
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const updated = await addStop(trip.id, {
        name: place.name.trim(),
        latitude: place.latitude,
        longitude: place.longitude,
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
          setError(t('stop.bookingNotSaved'));
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
    } catch (failure) {
      refused(failure);
      setSaving(false);
    }
  };

  /** Saves the edited stop; bookings outside its new days open the dialog, its choice saves again. */
  const saveChanges = async (stop: NewStop, trimBookings = false) => {
    if (!edited) {
      return;
    }
    setSaving(true);
    try {
      await updateStop(trip.id, edited.id, stop, trimBookings);
      setConflict(null);
      reload();
      close();
    } catch (failure) {
      setSaving(false);
      const found = stopBookingsConflict(failure);
      if (found) {
        setConflict({ stop, conflict: found });
        setConflictError(conflict ? t('stayConflict.failed') : null);
        return;
      }
      if (conflict) {
        setConflictError(t('stayConflict.failed'));
        return;
      }
      refused(failure);
    }
  };

  const extended = conflict && {
    ...conflict.stop,
    arrivalAt: conflict.conflict.extendedArrivalAt ?? conflict.stop.arrivalAt,
    departureAt: conflict.conflict.extendedDepartureAt ?? conflict.stop.departureAt,
  };

  return (
    <Sheet
      open
      onClose={close}
      title={edited ? t('stop.editTitle') : t('trip.addStop')}
      height="90vh"
      footer={
        <button type="submit" form="stop-form" className="va-button va-button-accent va-block" disabled={saving}>
          {saving && <><KiwiLoader size="small" decorative />{' '}</>}{saving ? t('common.saving') : edited ? t('stop.saveChanges') : t('stop.save')}
        </button>
      }
    >
      <form id="stop-form" className="va-form" onSubmit={(event) => void submit(event)} noValidate>
        <RequiredNote />
        {edited && (
          <Field id="stop-name" label={t('stop.name')} error={errors.name}>
            <input type="text" maxLength={255} value={name} onChange={(event) => setName(event.target.value)} onBlur={() => touch('name')} />
          </Field>
        )}
        <Field
          id="stop-place"
          label={t('stop.place')}
          required
          error={errors.place}
          hint={place
            ? place.detail ? t('stop.placeChosen', { detail: place.detail }) : edited ? t('stop.currentPlace') : t('stop.placeChosen', { detail: t('stop.placeSelected') })
            : t('stop.placeHint')}
        >
          <input
            type="search"
            autoComplete="off"
            value={place ? place.name : query}
            onChange={(event) => {
              setPlace(null);
              setQuery(event.target.value);
            }}
            onBlur={() => touch('place')}
          />
          {places.length > 0 && (
            <ul className="va-suggestions" aria-label={t('stop.suggestions')}>
              {places.map((candidate) => (
                <li key={`${candidate.latitude},${candidate.longitude}`}>
                  <button
                    type="button"
                    // keeps the focus in the search, so leaving it does not report a missing place first
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => {
                      setPlace(candidate);
                      // a new place renames the stop, the name can still be changed
                      setName(candidate.name);
                    }}
                  >
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
          {noMatch && !searchFailed && <span className="va-small va-muted">{t('stop.noPlace')}</span>}
          {searchFailed && <span className="va-small va-muted">{t('stop.searchUnavailable')}</span>}
        </Field>

        {!edited && (
          <div className="va-field">
            <span className="va-label">{t('stop.order')}</span>
            <span className="va-muted">
              {previous ? t('stop.orderAfter', { name: previous.name, position: trip.stops.length }) : t('stop.orderFirst')}
            </span>
          </div>
        )}

        <div className="va-field-row">
          <Field id="stop-arrival" label={t('stop.arrival')} error={errors.arrival}>
            <input type="datetime-local" value={arrival} onChange={(event) => setArrival(event.target.value)} onBlur={() => touch('arrival')} />
          </Field>
          <Field id="stop-departure" label={t('stop.departure')} error={errors.departure}>
            <input type="datetime-local" value={departure} min={arrival || undefined} onChange={(event) => setDeparture(event.target.value)}
              onBlur={() => touch('departure')} />
          </Field>
        </div>

        {edited ? null : booking ? (
          <div className="va-booking-row va-booking-draft">
            <span className="va-booking-icon" aria-hidden="true"><HomeOutlined /></span>
            <span className="va-column va-grow">
              <span className="va-strong">{booking.form.name}</span>
              <span className="va-small va-muted">
                {t('stop.draftBooking', { from: booking.form.checkInDate, to: booking.form.checkOutDate })}
              </span>
            </span>
            <button type="button" className="va-icon-button va-plain" aria-label={t('stop.removeBooking')} onClick={() => setBooking(null)}>
              <CloseOutlined />
            </button>
          </div>
        ) : (
          <button type="button" className="va-text-button va-add-booking" onClick={() => setAddingBooking(true)}>
            {t('stop.addBooking')}
          </button>
        )}
        {error && <Alert type="error" showIcon message={error} />}
      </form>
      {conflict && extended && (
        <StayConflictDialog
          title={t('stayConflict.stopTitle')}
          text={t('stayConflict.stopText', { count: conflict.conflict.bookings.length })}
          items={conflict.conflict.bookings.map(outsideBookingLabel)}
          extendLabel={t('stayConflict.extendStop')}
          extendDetail={t('stayConflict.extendDetail', { days: stopDaysLabel(extended.arrivalAt, extended.departureAt) })}
          trimLabel={t('stayConflict.trimBookings', { count: conflict.conflict.bookings.length })}
          trimDisabled={conflict.conflict.bookings.some((outside) => !outside.trimmable)}
          busy={saving}
          error={conflictError}
          onCancel={() => {
            setConflict(null);
            setConflictError(null);
          }}
          onExtend={() => void saveChanges(extended)}
          onTrim={() => void saveChanges(conflict.stop, true)}
        />
      )}
      {addingBooking && (
        <AddBookingSheet
          context={t('stop.newStop')}
          submitLabel={t('proposals.take.other')}
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
