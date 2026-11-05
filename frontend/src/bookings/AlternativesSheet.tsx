import { CheckCircleOutlined, EnvironmentOutlined, SearchOutlined, StarFilled } from '@ant-design/icons';
import { Alert, Spin } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router';

import Sheet from '../components/Sheet';
import { searchPlaces, type Place } from '../map/geocoding';
import { TripsApiError } from '../trips/tripsApi';
import { useTripContext } from '../trips/tripContext';
import {
  abortReservation,
  bookReservation,
  fetchAlternativesStatus,
  fetchCriteria,
  fetchReservation,
  markBookingCancelled,
  reserveAlternative,
  searchAlternatives,
  type AlternativesStatus,
  type Candidate,
  type Criteria,
  type Holder,
  type Reservation,
  type SearchResult,
} from './alternativesApi';
import { formatLocalDateTime } from './localTime';

type Step =
  | { kind: 'criteria' }
  | { kind: 'results'; result: SearchResult }
  | { kind: 'holder'; result: SearchResult; candidate: Candidate }
  | { kind: 'pay'; reservation: Reservation }
  | { kind: 'returned' }
  | { kind: 'booked'; reservation: Reservation };

const EMPTY_HOLDER: Holder = { firstName: '', lastName: '', email: '', phone: '' };

/**
 * Alternative to an accommodation booking that cannot be used: criteria from the booking, offers from LiteAPI
 * ranked by the assistant, booking paid with PayPal (redirect and back to this page), see
 * Docs/accommodation-alternatives.md.
 */
export default function AlternativesSheet() {
  const { bookingId } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { trip, bookings, reload, close } = useTripContext();
  const booking = bookings.find((candidate) => candidate.id === Number(bookingId));
  const returnedReservation = params.get('reservierung');
  const cancelled = params.get('abgebrochen') === '1';

  const [status, setStatus] = useState<AlternativesStatus | null>(null);
  const [criteria, setCriteria] = useState<Criteria | null>(null);
  const [step, setStep] = useState<Step>(returnedReservation ? { kind: 'returned' } : { kind: 'criteria' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [holder, setHolder] = useState<Holder>(EMPTY_HOLDER);
  const [oldMarked, setOldMarked] = useState(false);
  const completing = useRef(false);

  useEffect(() => {
    fetchAlternativesStatus().then(setStatus);
  }, []);

  // back from PayPal: book (or abort after cancelling there)
  useEffect(() => {
    if (!returnedReservation || completing.current) {
      return;
    }
    completing.current = true;
    const id = Number(returnedReservation);
    const run = cancelled ? abortReservation(id) : bookReservation(id);
    run.then(
      (reservation) => {
        if (reservation.status === 'BOOKED') {
          reload();
          setStep({ kind: 'booked', reservation });
        } else {
          setError(cancelled ? 'Die Zahlung wurde bei PayPal abgebrochen, es wurde nichts gebucht.' : failureText(reservation));
          setStep({ kind: 'criteria' });
          navigate('.', { replace: true });
        }
      },
      (failure) => {
        setError(errorText(failure));
        fetchReservation(id).then((reservation) => {
          if (reservation.status !== 'FAILED' && reservation.status !== 'BOOKED') {
            setStep({ kind: 'pay', reservation });
          } else {
            setStep({ kind: 'criteria' });
            navigate('.', { replace: true });
          }
        }, () => setStep({ kind: 'criteria' }));
      },
    );
  }, [returnedReservation, cancelled, navigate, reload]);

  useEffect(() => {
    if (step.kind === 'criteria' && !returnedReservation && booking && !criteria) {
      fetchCriteria(booking.id).then(setCriteria, () => setError('Die Angaben der Buchung konnten nicht geladen werden.'));
    }
  }, [step, returnedReservation, booking, criteria]);

  if (!booking) {
    return (
      <Sheet open onClose={close} title="Buchung nicht gefunden">
        <p className="va-muted">Diese Buchung gibt es nicht mehr.</p>
      </Sheet>
    );
  }

  const back = () => navigate(`/reisen/${trip.id}/buchungen/${booking.id}`);
  const search = async () => {
    if (!criteria) return;
    setBusy(true);
    setError(null);
    try {
      setStep({ kind: 'results', result: await searchAlternatives(booking.id, criteria) });
    } catch (failure) {
      setError(errorText(failure));
    } finally {
      setBusy(false);
    }
  };
  const reserve = async (candidate: Candidate) => {
    if (!criteria) return;
    setBusy(true);
    setError(null);
    try {
      setStep({ kind: 'pay', reservation: await reserveAlternative(booking.id, criteria, candidate, holder) });
    } catch (failure) {
      setError(errorText(failure));
    } finally {
      setBusy(false);
    }
  };
  const markOld = async () => {
    try {
      await markBookingCancelled(booking.id);
      setOldMarked(true);
      reload();
    } catch {
      setError('Die alte Buchung konnte nicht markiert werden.');
    }
  };

  const title = step.kind === 'booked' ? 'Alternative gebucht' : 'Alternative suchen';
  return (
    <Sheet open onClose={close} eyebrow={`statt ${booking.title}`} title={title} height="90vh"
      footer={footer()}>
      {status && !status.searchAvailable && step.kind === 'criteria' && (
        <Alert type="info" showIcon message="Die Suche nach Alternativen ist gerade nicht verfügbar." />
      )}
      {error && <Alert type="error" showIcon message={error} closable onClose={() => setError(null)} />}
      {step.kind === 'criteria' && (criteria
        ? <CriteriaForm criteria={criteria} onChange={setCriteria} />
        : <div className="va-empty"><Spin /></div>)}
      {step.kind === 'results' && (
        <Results result={step.result} criteria={criteria!} rankingAvailable={status?.rankingAvailable ?? false}
          onPick={(candidate) => setStep({ kind: 'holder', result: step.result, candidate })} />
      )}
      {step.kind === 'holder' && (
        <HolderForm candidate={step.candidate} holder={holder} onChange={setHolder} />
      )}
      {step.kind === 'pay' && <PaySummary reservation={step.reservation} />}
      {step.kind === 'returned' && (
        <div className="va-empty va-column va-gap"><Spin /><span className="va-muted">Zahlung wird geprüft und gebucht …</span></div>
      )}
      {step.kind === 'booked' && (
        <div className="va-form">
          <p className="va-strong"><CheckCircleOutlined aria-hidden="true" /> {step.reservation.hotelName} ist gebucht.</p>
          <p className="va-muted">
            Die Buchung steht jetzt beim Stop, bezahlt mit PayPal ({money(step.reservation.price, step.reservation.currency)}).
          </p>
          {booking.status !== 'CANCELLED' && !oldMarked ? (
            <div className="va-note">
              <p>Soll „{booking.title}“ als storniert markiert werden? Beim Anbieter stornierst du sie selbst.</p>
              <button type="button" className="va-button va-button-outline" onClick={() => void markOld()}>
                Als storniert markieren
              </button>
            </div>
          ) : (
            <p className="va-small va-muted">„{booking.title}“ ist als storniert markiert.</p>
          )}
        </div>
      )}
    </Sheet>
  );

  function footer() {
    switch (step.kind) {
      case 'criteria':
        return (
          <div className="va-row va-gap">
            <button type="button" className="va-button va-button-outline" onClick={back}>Zurück</button>
            <button type="button" className="va-button va-grow" disabled={busy || !criteria || !canSearch(criteria) || !status?.searchAvailable}
              onClick={() => void search()}>
              <SearchOutlined aria-hidden="true" /> {busy ? 'Suche läuft …' : 'Alternativen suchen'}
            </button>
          </div>
        );
      case 'results':
        return (
          <button type="button" className="va-button va-button-outline va-block" onClick={() => setStep({ kind: 'criteria' })}>
            Kriterien ändern
          </button>
        );
      case 'holder':
        return (
          <div className="va-row va-gap">
            <button type="button" className="va-button va-button-outline" onClick={() => setStep({ kind: 'results', result: step.result })}>
              Zurück
            </button>
            <button type="button" className="va-button va-grow" disabled={busy || !holderComplete(holder) || !status?.bookingAvailable}
              onClick={() => void reserve(step.candidate)}>
              {busy ? 'Preis wird gesichert …' : 'Weiter zur Zahlung'}
            </button>
          </div>
        );
      case 'pay':
        return step.reservation.approveUrl ? (
          <a className="va-button va-button-accent va-block" href={step.reservation.approveUrl}>
            Mit PayPal bezahlen ({money(step.reservation.price, step.reservation.currency)})
          </a>
        ) : (
          <button type="button" className="va-button va-block" disabled={busy}
            onClick={() => {
              setBusy(true);
              bookReservation(step.reservation.id).then(
                (reservation) => {
                  if (reservation.status === 'BOOKED') {
                    reload();
                    setStep({ kind: 'booked', reservation });
                  }
                },
                (failure) => setError(errorText(failure)),
              ).finally(() => setBusy(false));
            }}>
            Erneut versuchen
          </button>
        );
      case 'booked':
        return (
          <button type="button" className="va-button va-block"
            onClick={() => navigate(`/reisen/${trip.id}/buchungen/${step.reservation.bookingId}`)}>
            Neue Buchung ansehen
          </button>
        );
      default:
        return undefined;
    }
  }
}

function CriteriaForm({ criteria, onChange }: { criteria: Criteria; onChange: (criteria: Criteria) => void }) {
  const [placeQuery, setPlaceQuery] = useState('');
  const [places, setPlaces] = useState<Place[]>([]);
  const [changingPlace, setChangingPlace] = useState(criteria.latitude == null);
  const set = (patch: Partial<Criteria>) => onChange({ ...criteria, ...patch });
  const missing = new Set(criteria.missing);

  useEffect(() => {
    if (!changingPlace || placeQuery.trim().length < 3) {
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
  }, [placeQuery, changingPlace]);

  const mark = (field: string) => missing.has(field) ? 'va-field va-field-check' : 'va-field';
  return (
    <div className="va-form">
      <p className="va-small va-muted">
        Vorausgefüllt aus der Buchung. Gesucht werden Unterkünfte, die alle Kriterien erfüllen.
      </p>
      <div className={mark('location')}>
        <span className="va-label">Region</span>
        {!changingPlace ? (
          <span className="va-row va-gap va-baseline">
            <span className="va-muted va-grow"><EnvironmentOutlined aria-hidden="true" /> {criteria.placeName ?? 'Ort gewählt'}</span>
            <button type="button" className="va-text-button" onClick={() => setChangingPlace(true)}>ändern</button>
          </span>
        ) : (
          <>
            <input type="search" autoComplete="off" aria-label="Ort suchen" placeholder="Ort oder Adresse suchen"
              value={placeQuery} onChange={(event) => setPlaceQuery(event.target.value)} />
            {places.length > 0 && (
              <ul className="va-suggestions" aria-label="Vorschläge">
                {places.map((place) => (
                  <li key={`${place.latitude},${place.longitude}`}>
                    <button type="button" onClick={() => {
                      set({ latitude: place.latitude, longitude: place.longitude, placeName: [place.name, place.detail].filter(Boolean).join(', '),
                        missing: criteria.missing.filter((field) => field !== 'location') });
                      setChangingPlace(false);
                    }}>
                      <EnvironmentOutlined aria-hidden="true" />
                      <span className="va-column">
                        <span className="va-strong">{place.name}</span>
                        <span className="va-small va-muted">{place.detail}</span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </div>
      <div className="va-field">
        <label htmlFor="alt-radius">Umkreis: {(criteria.radiusMeters / 1000).toLocaleString('de-DE')} km</label>
        <input id="alt-radius" type="range" min={500} max={20000} step={500} value={criteria.radiusMeters}
          onChange={(event) => set({ radiusMeters: Number(event.target.value) })} />
      </div>
      <div className="va-field-row">
        <div className={mark('checkIn')}>
          <label htmlFor="alt-in">Anreise</label>
          <input id="alt-in" type="date" value={criteria.checkIn} onChange={(event) => set({ checkIn: event.target.value })} />
        </div>
        <div className={mark('checkOut')}>
          <label htmlFor="alt-out">Abreise</label>
          <input id="alt-out" type="date" min={criteria.checkIn || undefined} value={criteria.checkOut}
            onChange={(event) => set({ checkOut: event.target.value })} />
        </div>
      </div>
      <div className="va-field-row">
        <div className={mark('guests')}>
          <label htmlFor="alt-guests">Gäste</label>
          <input id="alt-guests" type="number" min={1} max={36} inputMode="numeric" value={criteria.guests ?? ''}
            onChange={(event) => set({ guests: event.target.value ? Number(event.target.value) : null })} />
        </div>
        <div className="va-field">
          <label htmlFor="alt-rooms">Zimmer</label>
          <input id="alt-rooms" type="number" min={1} max={9} inputMode="numeric" value={criteria.rooms}
            onChange={(event) => set({ rooms: Math.max(1, Number(event.target.value) || 1) })} />
        </div>
      </div>
      <div className="va-field-row">
        <div className="va-field">
          <label htmlFor="alt-price">Max. Preis gesamt</label>
          <input id="alt-price" type="text" inputMode="decimal" placeholder="ohne Grenze" value={criteria.maxPrice}
            onChange={(event) => set({ maxPrice: event.target.value })} />
        </div>
        <div className="va-field">
          <label htmlFor="alt-currency">Währung</label>
          <input id="alt-currency" type="text" maxLength={3} autoCapitalize="characters" value={criteria.currency}
            onChange={(event) => set({ currency: event.target.value.toUpperCase() })} />
        </div>
      </div>
    </div>
  );
}

function Results({ result, criteria, rankingAvailable, onPick }: {
  result: SearchResult;
  criteria: Criteria;
  rankingAvailable: boolean;
  onPick: (candidate: Candidate) => void;
}) {
  if (result.candidates.length === 0) {
    return (
      <div className="va-form">
        <p className="va-strong">Keine Unterkunft erfüllt alle Kriterien.</p>
        <p className="va-muted">
          {result.offersChecked > 0
            ? `${result.offersChecked} Angebote geprüft. Vergrößere den Umkreis oder erhöhe den Preis.`
            : 'Für diesen Zeitraum gibt es in der Region keine freien Zimmer. Vergrößere den Umkreis.'}
        </p>
      </div>
    );
  }
  return (
    <div className="va-form">
      <p className="va-small va-muted">
        {result.candidates.length} {result.candidates.length === 1 ? 'Unterkunft erfüllt' : 'Unterkünfte erfüllen'} alle
        Kriterien{criteria.maxPrice ? ` (bis ${money(criteria.maxPrice.replace(',', '.'), criteria.currency)})` : ''}.
        {result.ranked ? ' Sortiert vom Assistenten nach Gleichwertigkeit ✦' : rankingAvailable ? '' : ' Sortiert nach Preis.'}
      </p>
      <ul className="va-plain-list va-alternatives">
        {result.candidates.map((candidate) => (
          <li key={candidate.hotelId}>
            <button type="button" className={`va-option va-alternative ${candidate.hotelId === result.recommendedHotelId ? 'va-option-selected' : ''}`}
              onClick={() => onPick(candidate)}>
              {candidate.photoUrl && <img src={candidate.photoUrl} alt="" className="va-alternative-photo" loading="lazy" />}
              <span>
                {candidate.hotelId === result.recommendedHotelId && <span className="va-chip va-chip-ink">Empfehlung ✦</span>}
                <span className="va-option-title">{candidate.name}</span>
                <span className="va-small va-muted">
                  {[candidate.stars ? <span key="s">{candidate.stars} <StarFilled aria-label="Sterne" /></span> : null,
                    distance(candidate.distanceMeters), candidate.boardName].filter(Boolean)
                    .map((part, index) => <span key={index}>{index > 0 && ' · '}{part}</span>)}
                </span>
                <span className="va-strong">{money(candidate.price, candidate.currency)}</span>
                <span className="va-small">
                  {candidate.refundable
                    ? candidate.freeCancellationUntil
                      ? `Kostenlos stornierbar bis ${formatLocalDateTime(candidate.freeCancellationUntil)}`
                      : 'Kostenlos stornierbar'
                    : 'Nicht stornierbar'}
                </span>
                {candidate.possiblySame && <span className="va-small va-check-hint">Möglicherweise die bisherige Unterkunft</span>}
                {candidate.reason && <span className="va-small">✦ {candidate.reason}</span>}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

function HolderForm({ candidate, holder, onChange }: { candidate: Candidate; holder: Holder; onChange: (holder: Holder) => void }) {
  const set = (patch: Partial<Holder>) => onChange({ ...holder, ...patch });
  return (
    <div className="va-form">
      <div className="va-impact">
        <span className="va-eyebrow">{candidate.roomName ?? 'Unterkunft'}</span>
        <span className="va-strong">{candidate.name} · {money(candidate.price, candidate.currency)}</span>
      </div>
      <p className="va-small va-muted">
        Für wen wird gebucht? Name, E-Mail und Telefon gehen an LiteAPI und das Hotel und werden nach der Buchung
        bei uns gelöscht. Vor der Zahlung wird der Preis noch einmal gesichert.
      </p>
      <div className="va-field-row">
        <div className="va-field">
          <label htmlFor="alt-first">Vorname</label>
          <input id="alt-first" autoComplete="given-name" value={holder.firstName} onChange={(event) => set({ firstName: event.target.value })} />
        </div>
        <div className="va-field">
          <label htmlFor="alt-last">Nachname</label>
          <input id="alt-last" autoComplete="family-name" value={holder.lastName} onChange={(event) => set({ lastName: event.target.value })} />
        </div>
      </div>
      <div className="va-field">
        <label htmlFor="alt-email">E-Mail</label>
        <input id="alt-email" type="email" autoComplete="email" value={holder.email} onChange={(event) => set({ email: event.target.value })} />
      </div>
      <div className="va-field">
        <label htmlFor="alt-phone">Telefon</label>
        <input id="alt-phone" type="tel" autoComplete="tel" value={holder.phone} onChange={(event) => set({ phone: event.target.value })} />
      </div>
    </div>
  );
}

function PaySummary({ reservation }: { reservation: Reservation }) {
  return (
    <div className="va-form">
      <div className="va-impact">
        <span className="va-eyebrow">Preis gesichert</span>
        <span className="va-strong">{reservation.hotelName}</span>
      </div>
      <dl className="va-facts">
        <dt>Aufenthalt</dt>
        <dd>{date(reservation.checkIn)} – {date(reservation.checkOut)}</dd>
        <dt>Belegung</dt>
        <dd>{reservation.guests} {reservation.guests === 1 ? 'Gast' : 'Gäste'}, {reservation.rooms} Zimmer</dd>
        <dt>Preis</dt>
        <dd>{money(reservation.price, reservation.currency)} <span className="va-small va-muted">inkl. {money(reservation.fee, reservation.currency)} Servicegebühr</span></dd>
        <dt>Storno</dt>
        <dd>
          {reservation.refundable
            ? reservation.freeCancellationUntil ? `kostenlos bis ${formatLocalDateTime(reservation.freeCancellationUntil)}` : 'kostenlos stornierbar'
            : 'nicht stornierbar'}
        </dd>
      </dl>
      <p className="va-small va-muted">
        Bei PayPal wird der Betrag zunächst nur reserviert. Eingezogen wird er erst, wenn das Hotel die Buchung bestätigt
        hat; klappt die Buchung nicht, wird die Reservierung freigegeben.
      </p>
    </div>
  );
}

function canSearch(criteria: Criteria): boolean {
  return criteria.latitude != null && !!criteria.checkIn && !!criteria.checkOut && criteria.checkOut > criteria.checkIn
    && criteria.guests != null && criteria.guests >= criteria.rooms && /^[A-Z]{3}$/.test(criteria.currency)
    && (!criteria.maxPrice.trim() || Number(criteria.maxPrice.replace(',', '.')) > 0);
}

function holderComplete(holder: Holder): boolean {
  return !!holder.firstName.trim() && !!holder.lastName.trim() && /\S+@\S+\.\S+/.test(holder.email)
    && /^\+?[0-9 ()/-]{6,30}$/.test(holder.phone.trim());
}

function money(amount: string, currency: string): string {
  try {
    return new Intl.NumberFormat('de-DE', { style: 'currency', currency }).format(Number(amount));
  } catch {
    return `${amount} ${currency}`;
  }
}

function distance(meters: number): string {
  return meters < 1000 ? `${Math.round(meters / 10) * 10} m` : `${(meters / 1000).toLocaleString('de-DE', { maximumFractionDigits: 1 })} km`;
}

function date(value: string): string {
  const [year, month, day] = value.split('-');
  return `${day}.${month}.${year}`;
}

function failureText(reservation: Reservation): string {
  return reservation.status === 'FAILED'
    ? 'Die Buchung hat nicht geklappt, es wurde nichts abgebucht. Bitte wähle eine andere Unterkunft.'
    : 'Die Buchung ist noch nicht abgeschlossen.';
}

function errorText(failure: unknown): string {
  const code = failure instanceof TripsApiError ? failure.code : undefined;
  switch (code) {
    case 'OFFER_UNAVAILABLE':
      return 'Das Angebot ist nicht mehr verfügbar. Es wurde nichts abgebucht, bitte suche erneut.';
    case 'PRICE_EXCEEDED':
      return 'Der Preis ist inzwischen höher als dein Maximum. Bitte wähle ein anderes Angebot.';
    case 'PAYMENT_NOT_APPROVED':
      return 'Die Zahlung wurde bei PayPal noch nicht bestätigt.';
    case 'PAYMENT_DECLINED':
      return 'PayPal hat die Zahlung abgelehnt. Es wurde nichts gebucht.';
    case 'ALTERNATIVES_RATE_LIMITED':
      return 'Zu viele Suchen in kurzer Zeit. Bitte versuche es später noch einmal.';
    case 'ALTERNATIVES_UNAVAILABLE':
      return 'Der Buchungsdienst antwortet gerade nicht. Bitte versuche es gleich noch einmal.';
    case 'PAYPAL_NOT_CONFIGURED':
    case 'PAYPAL_ERROR':
      return 'PayPal ist gerade nicht erreichbar.';
    case 'INVALID_BOOKING_INPUT':
      return 'Bitte prüfe die Angaben.';
    default:
      return 'Das hat nicht geklappt. Bitte versuche es noch einmal.';
  }
}
