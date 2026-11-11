import { EnvironmentOutlined, HomeOutlined } from '@ant-design/icons';
import { useEffect, useRef, useState, type ReactNode } from 'react';

import KiwiLoader from '../components/KiwiLoader';
import { t } from '../i18n';
import { searchPlaces, type Place } from '../map/geocoding';
import { lookUpAccommodation, suggestAccommodations } from './bookingsApi';
import { liteApiHotelId, type AccommodationForm, type FormField } from './form';
import { shortTime } from './localTime';
import type { AccommodationFacts, AccommodationSuggestion } from './model';

interface Props {
  form: AccommodationForm;
  onChange: (form: AccommodationForm) => void;
  /** Filled by the assistant from a confirmation, marked with ✦. */
  filled?: Set<FormField>;
  /** To be checked by the user, highlighted. */
  uncertain?: Set<FormField>;
  /** Look the place up on the map once (after reading a confirmation). */
  autoLocate?: boolean;
  /** The stop: suggestions for the name near it come first. */
  near?: { latitude: number; longitude: number } | null;
}

type Suggestion =
  | { kind: 'liteapi'; key: string; suggestion: AccommodationSuggestion }
  | { kind: 'map'; key: string; place: Place };

type Origin = 'liteapi' | 'assistant';

interface Lookup {
  state: 'loading' | 'done' | 'failed';
  sources: string[];
}

const LOOKED_UP: FormField[] = ['phone', 'email', 'website', 'checkInFrom', 'checkInUntil', 'checkOutUntil', 'addressLine', 'countryCode'];

/** Short names of the looked up fields, for the note where they came from. */
const lookedUpLabel = (name: FormField) =>
  t(`accommodation.lookedUp.${name as 'addressLine' | 'countryCode' | 'phone' | 'email' | 'website' | 'checkInFrom' | 'checkInUntil' | 'checkOutUntil'}`);

/**
 * Fields of an accommodation booking. Typing the name suggests accommodations (LiteAPI hotel data and the map, near
 * the stop first); choosing one fills the address and looks up contact and house rules (LiteAPI, the rest found on
 * the web by the assistant). The stay is entered as dates; times are local at the accommodation.
 */
export default function AccommodationFields({ form, onChange, filled, uncertain, autoLocate, near }: Props) {
  const [autoLocated, setAutoLocated] = useState(false);
  const [suggesting, setSuggesting] = useState(false);
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [searching, setSearching] = useState(false);
  const [chosenName, setChosenName] = useState<string | null>(null);
  const [origins, setOrigins] = useState<Map<FormField, Origin>>(new Map());
  const [lookup, setLookup] = useState<Lookup | null>(null);
  const latest = useRef(form);
  latest.current = form;
  const lookupRun = useRef(0);
  const set = (patch: Partial<AccommodationForm>) => onChange({ ...latest.current, ...patch });

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
          onChange({ ...latest.current, ...placePatch(found[0]) });
        }
      })
      .catch(() => undefined);
    return () => controller.abort();
  }, [autoLocate, autoLocated, form, onChange]);

  // suggestions while typing the name: LiteAPI first (near the stop, nearest first), then accommodations on the map
  const query = form.name.trim();
  const nearLatitude = near?.latitude;
  const nearLongitude = near?.longitude;
  useEffect(() => {
    if (!suggesting || query.length < 3) {
      setSuggestions([]);
      setSearching(false);
      return;
    }
    const controller = new AbortController();
    const point = nearLatitude != null && nearLongitude != null ? { latitude: nearLatitude, longitude: nearLongitude } : null;
    setSearching(true);
    const timer = window.setTimeout(() => {
      Promise.all([
        suggestAccommodations(query, point, controller.signal),
        searchPlaces(query, controller.signal, { near: point, osmTag: 'tourism' }).catch(() => [] as Place[]),
      ]).then(([hotels, places]) => {
        if (!controller.signal.aborted) {
          setSuggestions(merge(hotels, places));
          setSearching(false);
        }
      }, () => setSearching(false));
    }, 350);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [query, suggesting, nearLatitude, nearLongitude]);

  const choose = (choice: Suggestion) => {
    setSuggesting(false);
    setSuggestions([]);
    const patch: Partial<AccommodationForm> = choice.kind === 'liteapi'
      ? {
        name: choice.suggestion.name,
        addressLine: choice.suggestion.addressLine ?? '',
        countryCode: choice.suggestion.countryCode ?? '',
        latitude: choice.suggestion.latitude,
        longitude: choice.suggestion.longitude,
        placeRef: `liteapi:${choice.suggestion.hotelId}`,
        placeDetail: [choice.suggestion.name, choice.suggestion.addressLine].filter(Boolean).join(', '),
      }
      : {
        name: choice.place.name,
        addressLine: choice.place.addressLine || choice.place.detail,
        countryCode: choice.place.countryCode ?? '',
        ...placePatch(choice.place),
      };
    // a new accommodation: what was looked up for the previous one goes
    const cleared: Partial<AccommodationForm> = {};
    origins.forEach((_, field) => {
      (cleared as Record<string, string>)[field] = '';
    });
    const next = { ...latest.current, ...cleared, ...patch };
    setChosenName(next.name);
    setOrigins(new Map());
    onChange(next);
    void lookUp(next);
  };

  const lookUp = async (chosen: AccommodationForm) => {
    const run = ++lookupRun.current;
    setLookup({ state: 'loading', sources: [] });
    let facts: AccommodationFacts;
    try {
      facts = await lookUpAccommodation({
        hotelId: liteApiHotelId(chosen),
        name: chosen.name,
        addressLine: chosen.addressLine || null,
        countryCode: chosen.countryCode || null,
        latitude: chosen.latitude,
        longitude: chosen.longitude,
      });
    } catch {
      if (run === lookupRun.current) {
        setLookup({ state: 'failed', sources: [] });
      }
      return;
    }
    if (run !== lookupRun.current) {
      return;
    }
    // fills only what is still empty, the user's input wins
    const current = latest.current;
    const patch: Partial<AccommodationForm> = {};
    const found = new Map<FormField, Origin>();
    const values: Partial<Record<FormField, string | null>> = {
      addressLine: facts.addressLine,
      countryCode: facts.countryCode,
      phone: facts.phone,
      email: facts.email,
      website: facts.website,
      checkInFrom: shortTime(facts.checkInFrom),
      checkInUntil: shortTime(facts.checkInUntil),
      checkOutUntil: shortTime(facts.checkOutUntil),
    };
    for (const field of LOOKED_UP) {
      const value = values[field];
      if (value && !String(current[field] ?? '').trim()) {
        (patch as Record<string, string>)[field] = value;
        found.set(field, facts.fromAssistant.includes(field) ? 'assistant' : 'liteapi');
      }
    }
    setOrigins(found);
    setLookup({ state: 'done', sources: facts.sources });
    onChange({ ...current, ...patch });
  };

  const field = (name: FormField, label: string, input: ReactNode, hint?: string) => {
    const researched = origins.get(name) === 'assistant';
    const check = uncertain?.has(name) || researched;
    return (
      <div className={`va-field ${check ? 'va-field-check' : ''}`}>
        <label htmlFor={`acc-${name}`}>
          {label}
          {(filled?.has(name) || researched) && (
            <span
              className="va-filled-mark"
              title={researched ? t('accommodation.researched') : t('accommodation.fromConfirmation')}
              aria-label={researched ? t('accommodation.researched') : t('accommodation.fromConfirmation')}
            />
          )}
        </label>
        {input}
        {check && <span className="va-small va-check-hint">{t('accommodation.check')}</span>}
        {hint && <span className="va-small va-muted">{hint}</span>}
      </div>
    );
  };
  const text = (name: FormField, type = 'text', extra: Record<string, unknown> = {}) => (
    <input
      id={`acc-${name}`}
      type={type}
      value={String(form[name] ?? '')}
      onChange={(event) => {
        if (origins.has(name)) {
          const next = new Map(origins);
          next.delete(name);
          setOrigins(next);
        }
        set({ [name]: event.target.value } as Partial<AccommodationForm>);
      }}
      {...extra}
    />
  );

  const fromLiteApi = [...origins].filter(([, origin]) => origin === 'liteapi').map(([name]) => lookedUpLabel(name));
  const fromAssistant = [...origins].filter(([, origin]) => origin === 'assistant').map(([name]) => lookedUpLabel(name));

  return (
    <div className="va-form">
      <h3 className="va-form-section">{t('addBooking.accommodation')}</h3>
      <div className={`va-field ${uncertain?.has('name') ? 'va-field-check' : ''}`}>
        <label htmlFor="acc-name">
          {t('accommodation.name')}
          {filled?.has('name') && (
            <span className="va-filled-mark" title={t('accommodation.fromConfirmation')} aria-label={t('accommodation.fromConfirmation')} />
          )}
        </label>
        <input
          id="acc-name"
          type="search"
          autoComplete="off"
          required
          aria-autocomplete="list"
          value={form.name}
          onChange={(event) => {
            const name = event.target.value;
            setSuggesting(true);
            // another accommodation than the one chosen: its place no longer fits
            if (chosenName !== null && name.trim() !== chosenName) {
              setChosenName(null);
              set({ name, latitude: null, longitude: null, placeRef: null, placeDetail: '' });
            } else {
              set({ name });
            }
          }}
          onKeyDown={(event) => event.key === 'Escape' && setSuggesting(false)}
          onBlur={() => window.setTimeout(() => setSuggesting(false), 200)}
        />
        {uncertain?.has('name') && <span className="va-small va-check-hint">{t('accommodation.check')}</span>}
        {(searching || !lookup) && (
          <span id="acc-name-hint" className="va-small va-muted">
            {searching && <><KiwiLoader size="small" decorative />{' '}</>}{searching ? t('accommodation.searching') : t('accommodation.nameHint')}
          </span>
        )}
        {suggesting && suggestions.length > 0 && (
          <ul className="va-suggestions" aria-label={t('stop.suggestions')}>
            {suggestions.map((choice) => (
              <li key={choice.key}>
                <button type="button" onMouseDown={(event) => event.preventDefault()} onClick={() => choose(choice)}>
                  {choice.kind === 'liteapi' ? <HomeOutlined aria-hidden="true" /> : <EnvironmentOutlined aria-hidden="true" />}
                  <span className="va-column">
                    <span className="va-strong">
                      {choice.kind === 'liteapi' ? choice.suggestion.name : choice.place.name}
                      {choice.kind === 'liteapi' && choice.suggestion.stars ? ` ${'★'.repeat(Math.round(choice.suggestion.stars))}` : ''}
                    </span>
                    <span className="va-small va-muted">
                      {choice.kind === 'liteapi' ? choice.suggestion.addressLine : choice.place.detail}
                    </span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {lookup && <LookupNote lookup={lookup} fromLiteApi={fromLiteApi} fromAssistant={fromAssistant} />}
      </div>
      {field(
        'addressLine',
        t('booking.address'),
        text('addressLine', 'text', { autoComplete: 'off' }),
        form.latitude == null ? t('accommodation.noPlace') : undefined,
      )}

      <h3 className="va-form-section">{t('booking.stay')}</h3>
      <div className="va-field-row">
        {field('checkInDate', t('booking.checkIn'), text('checkInDate', 'date', { required: true }))}
        {field('checkOutDate', t('booking.checkOut'), text('checkOutDate', 'date', { required: true, min: form.checkInDate || undefined }))}
      </div>

      <h3 className="va-form-section">{t('accommodation.booking')}</h3>
      {field('provider', t('accommodation.provider'), text('provider', 'text', { placeholder: t('accommodation.providerPlaceholder'), autoComplete: 'off' }))}
      {field('reference', t('accommodation.reference'), text('reference', 'text', { autoComplete: 'off' }))}
      {field('manageUrl', t('form.manageUrl'), text('manageUrl', 'url', { placeholder: 'https://…', inputMode: 'url' }))}
      <div className="va-field-row">
        {field('guests', t('form.guests'), text('guests', 'number', { min: 1, max: 99, inputMode: 'numeric' }))}
        {field('rooms', t('form.rooms'), text('rooms', 'number', { min: 1, max: 99, inputMode: 'numeric' }))}
      </div>

      <details className="va-details" open={hasMore(form, filled, uncertain, origins)}>
        <summary>{t('accommodation.more')}</summary>
        <div className="va-form">
          <div className="va-field-row">
            {field('checkInFrom', t('accommodation.lookedUp.checkInFrom'), text('checkInFrom', 'time'))}
            {field('checkInUntil', t('accommodation.lookedUp.checkInUntil'), text('checkInUntil', 'time'))}
          </div>
          {field('checkOutUntil', t('accommodation.lookedUp.checkOutUntil'), text('checkOutUntil', 'time'))}
          {field('phone', t('accommodation.phone'), text('phone', 'tel'))}
          {field('email', t('accommodation.email'), text('email', 'email'))}
          {field('website', t('form.website'), text('website', 'url', { placeholder: 'https://…', inputMode: 'url' }))}
          <div className="va-field-row">
            {field('price', t('accommodation.price'), text('price', 'text', { inputMode: 'decimal', placeholder: t('accommodation.pricePlaceholder') }))}
            {field('currency', t('accommodation.currency'), text('currency', 'text', { maxLength: 3, autoCapitalize: 'characters' }))}
          </div>
          {field('cancellationDeadline', t('accommodation.freeCancellation'), text('cancellationDeadline', 'datetime-local'))}
          <div className="va-field">
            <label htmlFor="acc-notes">{t('booking.notes')}</label>
            <textarea id="acc-notes" rows={3} value={form.notes} onChange={(event) => set({ notes: event.target.value })} />
          </div>
        </div>
      </details>
    </div>
  );
}

/** Where contact and house rules of the chosen accommodation came from. */
function LookupNote({ lookup, fromLiteApi, fromAssistant }: { lookup: Lookup; fromLiteApi: (string | undefined)[]; fromAssistant: (string | undefined)[] }) {
  if (lookup.state === 'loading') {
    return (
      <span className="va-small va-muted" role="status">
        <KiwiLoader size="small" decorative /> {t('accommodation.lookingUp')}
      </span>
    );
  }
  if (lookup.state === 'failed') {
    return <span className="va-small va-muted" role="status">{t('accommodation.lookupFailed')}</span>;
  }
  if (!fromLiteApi.length && !fromAssistant.length) {
    return <span className="va-small va-muted" role="status">{t('accommodation.nothingFound')}</span>;
  }
  return (
    <span className="va-small va-muted" role="status">
      {fromLiteApi.length > 0 && <>{t('accommodation.fromHotelData', { fields: fromLiteApi.join(', ') })} </>}
      {fromAssistant.length > 0 && (
        <>
          {t('accommodation.fromWeb', { fields: fromAssistant.join(', ') })}
          {lookup.sources.length > 0 && (
            <>
              {' '}(
              {lookup.sources.map((source, index) => (
                <span key={source}>
                  {index > 0 && ', '}
                  <a href={source} target="_blank" rel="noreferrer noopener">{hostOf(source)}</a>
                </span>
              ))}
              )
            </>
          )}
          .
        </>
      )}
    </span>
  );
}

function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return t('accommodation.source');
  }
}

/** LiteAPI first; map results only if they are not the same accommodation (same name close by). */
function merge(hotels: AccommodationSuggestion[], places: Place[]): Suggestion[] {
  const result: Suggestion[] = hotels.map((suggestion) => ({ kind: 'liteapi', key: `liteapi:${suggestion.hotelId}`, suggestion }));
  for (const place of places) {
    const duplicate = hotels.some(
      (hotel) =>
        hotel.name.trim().toLowerCase() === place.name.trim().toLowerCase() &&
        (hotel.latitude == null || hotel.longitude == null ||
          (Math.abs(hotel.latitude - place.latitude) < 0.01 && Math.abs(hotel.longitude - place.longitude) < 0.015)),
    );
    if (!duplicate) {
      result.push({ kind: 'map', key: place.osmRef ?? `${place.latitude},${place.longitude}`, place });
    }
  }
  return result.slice(0, 8);
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
  'price', 'cancellationDeadline', 'checkInFrom', 'checkInUntil', 'checkOutUntil', 'phone', 'email', 'website',
];

function hasMore(form: AccommodationForm, filled?: Set<FormField>, uncertain?: Set<FormField>, origins?: Map<FormField, Origin>) {
  return (
    MORE_FIELDS.some((name) => uncertain?.has(name) || filled?.has(name) || origins?.has(name)) || !!form.notes
  );
}
