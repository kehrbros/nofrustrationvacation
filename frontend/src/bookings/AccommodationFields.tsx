import { EnvironmentOutlined } from '@ant-design/icons';
import { useEffect, useState, type ReactNode } from 'react';

import { searchPlaces, type Place } from '../map/geocoding';
import type { AccommodationForm, FormField } from './form';

interface Props {
  form: AccommodationForm;
  onChange: (form: AccommodationForm) => void;
  /** Filled by the assistant, marked with ✦. */
  filled?: Set<FormField>;
  /** To be checked by the user, highlighted. */
  uncertain?: Set<FormField>;
  /** Look the place up on the map once (after reading a confirmation). */
  autoLocate?: boolean;
}

/** Fields of an accommodation booking; times are local at the accommodation. */
export default function AccommodationFields({ form, onChange, filled, uncertain, autoLocate }: Props) {
  const [placeQuery, setPlaceQuery] = useState('');
  const [places, setPlaces] = useState<Place[]>([]);
  const [searching, setSearching] = useState(false);
  const [autoLocated, setAutoLocated] = useState(false);
  const set = (patch: Partial<AccommodationForm>) => onChange({ ...form, ...patch });

  // after reading a confirmation: find the accommodation on the map, the user can change it
  useEffect(() => {
    if (!autoLocate || autoLocated || form.latitude != null || !form.name.trim()) {
      return;
    }
    setAutoLocated(true);
    const controller = new AbortController();
    const query = [form.name, form.addressLine].filter(Boolean).join(', ');
    searchPlaces(query, controller.signal)
      .then(async (found) => found.length ? found : searchPlaces(form.addressLine || form.name, controller.signal))
      .then((found) => {
        if (found[0]) {
          onChange({ ...form, ...placePatch(found[0]) });
        }
      })
      .catch(() => undefined);
    return () => controller.abort();
  }, [autoLocate, autoLocated, form, onChange]);

  useEffect(() => {
    if (!searching || placeQuery.trim().length < 3) {
      setPlaces([]);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      searchPlaces(placeQuery.trim(), controller.signal).then(setPlaces, () => undefined);
    }, 300);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [placeQuery, searching]);

  const field = (name: FormField, label: string, input: ReactNode, hint?: string) => (
    <div className={`va-field ${uncertain?.has(name) ? 'va-field-check' : ''}`}>
      <label htmlFor={`acc-${name}`}>
        {label}
        {filled?.has(name) && (
          <span className="va-ai-mark" title="Aus der Bestätigung gelesen" aria-label="aus der Bestätigung gelesen">
            {' '}✦
          </span>
        )}
      </label>
      {input}
      {uncertain?.has(name) && <span className="va-small va-check-hint">Bitte prüfen</span>}
      {hint && <span className="va-small va-muted">{hint}</span>}
    </div>
  );
  const text = (name: FormField, type = 'text', extra: Record<string, unknown> = {}) => (
    <input
      id={`acc-${name}`}
      type={type}
      value={String(form[name] ?? '')}
      onChange={(event) => set({ [name]: event.target.value } as Partial<AccommodationForm>)}
      {...extra}
    />
  );

  return (
    <div className="va-form">
      <h3 className="va-form-section">Unterkunft</h3>
      {field('name', 'Name', text('name', 'text', { autoComplete: 'off', required: true }))}
      {field('addressLine', 'Adresse', text('addressLine', 'text', { autoComplete: 'off' }))}
      <div className="va-field">
        <span className="va-label">Auf der Karte</span>
        {form.latitude != null && !searching ? (
          <span className="va-row va-gap va-baseline">
            <span className="va-muted va-grow">
              <EnvironmentOutlined aria-hidden="true" /> {form.placeDetail || 'Ort gewählt'}
            </span>
            <button type="button" className="va-text-button" onClick={() => { setSearching(true); setPlaceQuery(form.name); }}>
              ändern
            </button>
          </span>
        ) : (
          <>
            <input
              id="acc-place"
              type="search"
              autoComplete="off"
              aria-label="Unterkunft auf der Karte suchen"
              placeholder="Name oder Adresse suchen"
              value={searching ? placeQuery : ''}
              onFocus={() => { if (!searching) { setSearching(true); setPlaceQuery(form.name); } }}
              onChange={(event) => setPlaceQuery(event.target.value)}
            />
            <span className="va-small va-muted">
              Mit Kartenposition stimmen Ortszeit und Route. Ohne gilt die Zeitzone dieses Geräts.
            </span>
          </>
        )}
        {searching && places.length > 0 && (
          <ul className="va-suggestions" aria-label="Vorschläge">
            {places.map((candidate) => (
              <li key={`${candidate.latitude},${candidate.longitude}`}>
                <button
                  type="button"
                  onClick={() => {
                    set(placePatch(candidate));
                    setSearching(false);
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
      </div>

      <h3 className="va-form-section">Aufenthalt <span className="va-small va-muted">(Ortszeit der Unterkunft)</span></h3>
      <div className="va-field-row">
        {field('checkInDate', 'Anreise', text('checkInDate', 'date', { required: true }))}
        {field('checkInTime', 'Check-in', text('checkInTime', 'time', { required: true }))}
      </div>
      <div className="va-field-row">
        {field('checkOutDate', 'Abreise', text('checkOutDate', 'date', { required: true, min: form.checkInDate || undefined }))}
        {field('checkOutTime', 'Check-out', text('checkOutTime', 'time', { required: true }))}
      </div>

      <h3 className="va-form-section">Buchung</h3>
      {field('provider', 'Gebucht über', text('provider', 'text', { placeholder: 'z. B. Booking.com', autoComplete: 'off' }))}
      {field('reference', 'Buchungsnummer', text('reference', 'text', { autoComplete: 'off' }), 'Die PIN brauchen wir nicht.')}
      {field('manageUrl', 'Link zur Buchung', text('manageUrl', 'url', { placeholder: 'https://…', inputMode: 'url' }))}

      <details className="va-details" open={hasMore(form, filled, uncertain)}>
        <summary>Preis, Storno, Kontakt und mehr</summary>
        <div className="va-form">
          <div className="va-field-row">
            {field('price', 'Preis gesamt', text('price', 'text', { inputMode: 'decimal', placeholder: '0,00' }))}
            {field('currency', 'Währung', text('currency', 'text', { maxLength: 3, autoCapitalize: 'characters' }))}
          </div>
          {field('cancellationDeadline', 'Kostenlos stornierbar bis', text('cancellationDeadline', 'datetime-local'))}
          <div className="va-field-row">
            {field('guests', 'Gäste', text('guests', 'number', { min: 1, max: 99, inputMode: 'numeric' }))}
            {field('rooms', 'Zimmer', text('rooms', 'number', { min: 1, max: 99, inputMode: 'numeric' }))}
          </div>
          <div className="va-field-row">
            {field('checkInFrom', 'Check-in ab', text('checkInFrom', 'time'))}
            {field('checkInUntil', 'Check-in bis', text('checkInUntil', 'time'))}
          </div>
          {field('checkOutUntil', 'Check-out bis', text('checkOutUntil', 'time'))}
          {field('phone', 'Telefon der Unterkunft', text('phone', 'tel'))}
          {field('email', 'E-Mail der Unterkunft', text('email', 'email'))}
          {field('website', 'Website', text('website', 'url', { placeholder: 'https://…', inputMode: 'url' }))}
          <div className="va-field">
            <label htmlFor="acc-notes">Notiz</label>
            <textarea id="acc-notes" rows={3} value={form.notes} onChange={(event) => set({ notes: event.target.value })} />
          </div>
        </div>
      </details>
    </div>
  );
}

function placePatch(place: Place): Partial<AccommodationForm> {
  return {
    latitude: place.latitude,
    longitude: place.longitude,
    placeRef: place.osmRef ?? null,
    placeDetail: [place.name, place.detail].filter(Boolean).join(', '),
  };
}

const MORE_FIELDS: FormField[] = [
  'price', 'cancellationDeadline', 'guests', 'rooms', 'checkInFrom', 'checkInUntil', 'checkOutUntil', 'phone',
  'email', 'website',
];

function hasMore(form: AccommodationForm, filled?: Set<FormField>, uncertain?: Set<FormField>) {
  return MORE_FIELDS.some((name) => uncertain?.has(name) || (filled?.has(name) && name !== 'currency')) || !!form.notes;
}
