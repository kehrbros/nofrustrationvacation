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
import { t } from '../i18n';
import { searchPlaces, type Place } from '../map/geocoding';
import { useTripContext } from '../trips/tripContext';
import { localDateTimeIso } from '../trips/time';
import { addStop, TripsApiError } from '../trips/tripsApi';

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
  const [noMatch, setNoMatch] = useState(false);
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
    // stops need a position in New Zealand, only places from the suggestions have one
    if (!place) {
      setError(t('stop.choosePlace'));
      return;
    }
    if (arrivalAt && departureAt && new Date(departureAt) < new Date(arrivalAt)) {
      setError(t('stop.departureBeforeArrival'));
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
      setError(failure instanceof TripsApiError && failure.code === 'LOCATION_OUTSIDE_REGIONS'
        ? t('stop.outsideRegions')
        : t('stop.saveFailed'));
      setSaving(false);
    }
  };

  return (
    <Sheet
      open
      onClose={close}
      title={t('trip.addStop')}
      height="90vh"
      footer={
        <button type="submit" form="stop-form" className="va-button va-button-accent va-block" disabled={saving}>
          {saving ? t('common.saving') : t('stop.save')}
        </button>
      }
    >
      <form id="stop-form" className="va-form" onSubmit={(event) => void submit(event)} noValidate>
        <div className="va-field">
          <label htmlFor="stop-place">{t('stop.place')}</label>
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
            {place ? t('stop.placeChosen', { detail: place.detail || t('stop.placeSelected') }) : t('stop.placeHint')}
          </span>
          {places.length > 0 && (
            <ul className="va-suggestions" aria-label={t('stop.suggestions')}>
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
          {noMatch && !searchFailed && <span className="va-small va-muted">{t('stop.noPlace')}</span>}
          {searchFailed && <span className="va-small va-muted">{t('stop.searchUnavailable')}</span>}
        </div>

        <div className="va-field">
          <span className="va-label">{t('stop.order')}</span>
          <span className="va-muted">
            {previous ? t('stop.orderAfter', { name: previous.name, position: trip.stops.length }) : t('stop.orderFirst')}
          </span>
        </div>

        <div className="va-field-row">
          <div className="va-field">
            <label htmlFor="stop-arrival">{t('stop.arrival')}</label>
            <input id="stop-arrival" type="datetime-local" value={arrival} onChange={(event) => setArrival(event.target.value)} />
          </div>
          <div className="va-field">
            <label htmlFor="stop-departure">{t('stop.departure')}</label>
            <input id="stop-departure" type="datetime-local" value={departure} min={arrival || undefined} onChange={(event) => setDeparture(event.target.value)} />
          </div>
        </div>

        {booking ? (
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
