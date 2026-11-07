import { CheckCircleOutlined, CheckOutlined, EnvironmentOutlined, LoadingOutlined, NodeIndexOutlined, SearchOutlined, StarFilled } from '@ant-design/icons';
import { Alert, Spin } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';

import Sheet from '../components/Sheet';
import { searchPlaces, type Place } from '../map/geocoding';
import PayPalButton from '../payments/PayPalButton';
import { substituteDeparture } from '../trips/routeOptionText';
import { fetchSuggestions } from '../trips/routesApi';
import { TripsApiError } from '../trips/tripsApi';
import { routeName, useTripContext } from '../trips/tripContext';
import {
  abortReservation,
  bookReservation,
  fetchAlternativesStatus,
  fetchCriteria,
  fetchReservation,
  fetchRouteCheck,
  markBookingCancelled,
  rememberedHolder,
  rememberHolder,
  reserveAlternative,
  searchAlternatives,
  type AlternativesStatus,
  type Candidate,
  type Criteria,
  type Holder,
  type Reservation,
  type RouteCheck,
  type SearchResult,
} from './alternativesApi';
import { formatLocalDateTime } from './localTime';
import { routeCheckText } from './routeCheckText';
import { directionsUrl } from './text';

type Step =
  | { kind: 'criteria' }
  | { kind: 'searching' }
  | { kind: 'results'; result: SearchResult }
  | { kind: 'holder'; result: SearchResult; candidate: Candidate }
  | { kind: 'reserving'; candidate: Candidate }
  | { kind: 'pay'; reservation: Reservation; result: SearchResult | null }
  | { kind: 'booking'; reservation: Reservation }
  | { kind: 'returned' }
  | { kind: 'booked'; reservation: Reservation }
  | { kind: 'attention'; reservation: Reservation };

const EMPTY_HOLDER: Holder = { firstName: '', lastName: '', email: '', phone: '' };

/**
 * Alternative to an accommodation booking that cannot be used: criteria from the booking (searched right away when
 * complete), offers that meet all of them with a recommendation, booking paid with the PayPal button in the sheet
 * (money only reserved until the hotel confirmed). A redirect to PayPal is the fallback, it comes back to this page.
 * See Docs/accommodation-alternatives.md and Docs/paypal.md.
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
  const [notice, setNotice] = useState<string | null>(null);
  const [holder, setHolder] = useState<Holder>(() => rememberedHolder() ?? EMPTY_HOLDER);
  const [oldMarked, setOldMarked] = useState(false);
  const completing = useRef(false);
  const autoSearched = useRef(false);

  useEffect(() => {
    fetchAlternativesStatus().then(setStatus);
  }, []);

  /** Result of booking (after PayPal): booked, to be checked, or back to choosing. */
  const finished = (reservation: Reservation) => {
    if (reservation.status === 'BOOKED') {
      reload();
      setStep({ kind: 'booked', reservation });
    } else if (reservation.status === 'NEEDS_ATTENTION' || reservation.status === 'PROVIDER_BOOKED') {
      reload();
      setStep({ kind: 'attention', reservation });
    } else if (reservation.status === 'FAILED') {
      setError(failureText(reservation));
      setStep({ kind: 'criteria' });
    } else {
      setStep({ kind: 'pay', reservation, result: null });
    }
  };

  const book = (reservation: Reservation) => {
    setError(null);
    setNotice(null);
    setStep({ kind: 'booking', reservation });
    bookReservation(reservation.id).then(finished, (failure) => {
      setError(errorText(failure));
      fetchReservation(reservation.id).then(finished, () => setStep({ kind: 'pay', reservation, result: null }));
    });
  };

  // back from PayPal after the redirect fallback: book (or abort after cancelling there)
  useEffect(() => {
    if (!returnedReservation || completing.current) {
      return;
    }
    completing.current = true;
    const id = Number(returnedReservation);
    navigate('.', { replace: true });
    if (cancelled) {
      abortReservation(id).then(
        () => setError('Die Zahlung wurde bei PayPal abgebrochen, es wurde nichts gebucht.'),
        () => undefined,
      ).finally(() => setStep({ kind: 'criteria' }));
      return;
    }
    bookReservation(id).then(finished, (failure) => {
      setError(errorText(failure));
      fetchReservation(id).then(finished, () => setStep({ kind: 'criteria' }));
    });
  }, [returnedReservation, cancelled]);

  useEffect(() => {
    if (step.kind === 'criteria' && booking && !criteria) {
      fetchCriteria(booking.id).then(setCriteria, () => setError('Die Angaben der Buchung konnten nicht geladen werden.'));
    }
  }, [step, booking, criteria]);

  // everything known from the booking: search right away, the form is only for changes
  useEffect(() => {
    if (!autoSearched.current && step.kind === 'criteria' && criteria && status?.searchAvailable
      && criteria.missing.length === 0 && canSearch(criteria)) {
      autoSearched.current = true;
      void search();
    }
  }, [step, criteria, status]);

  if (!booking) {
    return (
      <Sheet open onClose={close} title="Buchung nicht gefunden">
        <p className="va-muted">Diese Buchung gibt es nicht mehr.</p>
      </Sheet>
    );
  }

  const back = () => navigate(`/reisen/${trip.id}/buchungen/${booking.id}`);
  async function search() {
    if (!criteria) return;
    setBusy(true);
    setError(null);
    setStep({ kind: 'searching' });
    try {
      setStep({ kind: 'results', result: await searchAlternatives(booking!.id, criteria) });
    } catch (failure) {
      setError(errorText(failure));
      setStep({ kind: 'criteria' });
    } finally {
      setBusy(false);
    }
  }
  const reserve = async (candidate: Candidate, result: SearchResult) => {
    if (!criteria) return;
    setBusy(true);
    setError(null);
    setStep({ kind: 'reserving', candidate });
    try {
      const reservation = await reserveAlternative(booking.id, criteria, candidate, holder);
      rememberHolder(holder);
      setStep({ kind: 'pay', reservation, result });
    } catch (failure) {
      setError(errorText(failure));
      setStep({ kind: 'results', result });
    } finally {
      setBusy(false);
    }
  };
  const pick = (candidate: Candidate, result: SearchResult) => {
    if (holderComplete(holder)) {
      void reserve(candidate, result);
    } else {
      setStep({ kind: 'holder', result, candidate });
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

  const title = step.kind === 'booked' ? 'Umgebucht' : step.kind === 'attention' ? 'Gebucht, Zahlung wird geprüft'
    : 'Andere Unterkunft';
  return (
    <Sheet open onClose={close} eyebrow={`statt ${booking.title}`} title={title} height="90vh"
      footer={footer()}>
      {status && !status.searchAvailable && step.kind === 'criteria' && (
        <Alert type="info" showIcon message="Die Suche nach Alternativen ist gerade nicht verfügbar." />
      )}
      {error && <Alert type="error" showIcon message={error} closable onClose={() => setError(null)} />}
      {notice && <Alert type="info" showIcon message={notice} closable onClose={() => setNotice(null)} />}
      {step.kind === 'criteria' && (criteria
        ? <CriteriaForm criteria={criteria} onChange={setCriteria} />
        : <div className="va-empty"><Spin /></div>)}
      {step.kind === 'searching' && (
        <div className="va-empty va-column va-gap"><Spin /><span className="va-muted">Freie Unterkünfte werden gesucht und verglichen …</span></div>
      )}
      {step.kind === 'results' && (
        <Results result={step.result} criteria={criteria!} onPick={(candidate) => pick(candidate, step.result)} />
      )}
      {step.kind === 'holder' && (
        <HolderForm candidate={step.candidate} holder={holder} onChange={setHolder} />
      )}
      {step.kind === 'reserving' && (
        <div className="va-empty va-column va-gap"><Spin /><span className="va-muted">Preis bei {step.candidate.name} wird gesichert …</span></div>
      )}
      {step.kind === 'pay' && <PaySummary reservation={step.reservation} holder={holder} />}
      {step.kind === 'booking' && <BookingProgress reservation={step.reservation} />}
      {step.kind === 'returned' && (
        <div className="va-empty va-column va-gap"><Spin /><span className="va-muted">Zahlung wird geprüft und gebucht …</span></div>
      )}
      {step.kind === 'booked' && (
        <Booked reservation={step.reservation} oldTitle={booking.title} oldManageUrl={booking.manageUrl}
          oldProvider={booking.provider} oldMarked={booking.status === 'CANCELLED' || oldMarked} onMarkOld={() => void markOld()} />
      )}
      {step.kind === 'attention' && (
        <div className="va-form">
          <p className="va-strong"><CheckCircleOutlined aria-hidden="true" /> {step.reservation.hotelName} ist gebucht.</p>
          <p className="va-muted">
            Die Zahlung bei PayPal ist noch nicht abgeschlossen. Wir kümmern uns darum, du musst nichts tun. Der Betrag
            ist bei PayPal nur reserviert, solange das nicht geklärt ist.
          </p>
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
              <SearchOutlined aria-hidden="true" /> {busy ? 'Suche läuft …' : 'Unterkünfte suchen'}
            </button>
          </div>
        );
      case 'results':
        return (
          <button type="button" className="va-button va-button-outline va-block" onClick={() => setStep({ kind: 'criteria' })}>
            Suche anpassen
          </button>
        );
      case 'holder':
        return (
          <div className="va-row va-gap">
            <button type="button" className="va-button va-button-outline" onClick={() => setStep({ kind: 'results', result: step.result })}>
              Zurück
            </button>
            <button type="button" className="va-button va-grow" disabled={busy || !holderComplete(holder) || !status?.bookingAvailable}
              onClick={() => void reserve(step.candidate, step.result)}>
              Weiter zur Zahlung
            </button>
          </div>
        );
      case 'pay':
        return (
          <div className="va-column va-gap">
            <PayPalButton
              clientId={status?.payPalClientId ?? null}
              currency={step.reservation.currency}
              orderId={step.reservation.payPalOrderId}
              approveUrl={step.reservation.approveUrl}
              onApprove={() => book(step.reservation)}
              onCancel={() => setNotice('Zahlung abgebrochen, es wurde nichts gebucht. Du kannst es noch einmal versuchen.')}
              onError={() => setError('PayPal konnte die Zahlung nicht starten. Bitte versuche es noch einmal.')}
            />
            {!step.reservation.payPalOrderId && !step.reservation.approveUrl && (
              <button type="button" className="va-button va-block" onClick={() => book(step.reservation)}>
                Erneut versuchen
              </button>
            )}
            {step.result && (
              <button type="button" className="va-text-button" onClick={() => {
                void abortReservation(step.reservation.id).catch(() => undefined);
                setStep({ kind: 'results', result: step.result! });
              }}>
                Andere Unterkunft wählen
              </button>
            )}
          </div>
        );
      case 'booked':
        return (
          <button type="button" className="va-button va-block"
            onClick={() => navigate(`/reisen/${trip.id}/buchungen/${step.reservation.bookingId}`)}>
            Neue Buchung ansehen
          </button>
        );
      case 'attention':
        return (
          <button type="button" className="va-button va-block" onClick={close}>Zur Reise</button>
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
        Übernommen aus deiner Buchung. Wir zeigen nur Unterkünfte, die alles davon erfüllen.
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

function Results({ result, criteria, onPick }: {
  result: SearchResult;
  criteria: Criteria;
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
        {result.candidates.length} {result.candidates.length === 1 ? 'freie Unterkunft' : 'freie Unterkünfte'} für
        {' '}{criteriaSummary(criteria)}. {result.ranked ? 'Die passendste zuerst.' : 'Die günstigste zuerst.'}
      </p>
      <ul className="va-plain-list va-alternatives">
        {result.candidates.map((candidate) => (
          <li key={candidate.hotelId}>
            <button type="button" className={`va-option va-alternative ${candidate.hotelId === result.recommendedHotelId ? 'va-option-selected' : ''}`}
              onClick={() => onPick(candidate)}>
              {candidate.photoUrl && <img src={candidate.photoUrl} alt="" className="va-alternative-photo" loading="lazy" />}
              <span>
                {candidate.hotelId === result.recommendedHotelId && <span className="va-chip va-chip-ink">Unsere Empfehlung</span>}
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
                {candidate.reason && <span className="va-small">{candidate.reason}</span>}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** What is booked, as the holder form shows it: an offer of the search or of a proposal. */
export interface HolderOffer {
  name: string;
  price: string;
  currency: string;
  roomName?: string | null;
}

export function HolderForm({ candidate, holder, onChange }: { candidate: HolderOffer; holder: Holder; onChange: (holder: Holder) => void }) {
  const set = (patch: Partial<Holder>) => onChange({ ...holder, ...patch });
  return (
    <div className="va-form">
      <div className="va-impact">
        <span className="va-eyebrow">{candidate.roomName ?? 'Unterkunft'}</span>
        <span className="va-strong">{candidate.name} · {money(candidate.price, candidate.currency)}</span>
      </div>
      <p className="va-small va-muted">
        Für wen wird gebucht? Name, E-Mail und Telefon gehen an das Hotel und werden nach der Buchung bei uns
        gelöscht. Auf diesem Gerät merken wir sie uns für die nächste Umbuchung. Vor der Zahlung wird der Preis noch
        einmal gesichert.
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

export function PaySummary({ reservation, holder }: { reservation: Reservation; holder: Holder }) {
  return (
    <div className="va-form">
      <div className="va-impact">
        <span className="va-eyebrow">Preis gesichert</span>
        <span className="va-strong">{reservation.hotelName}</span>
        <span className="va-strong">{money(reservation.price, reservation.currency)}</span>
      </div>
      <dl className="va-facts">
        <dt>Aufenthalt</dt>
        <dd>{date(reservation.checkIn)} – {date(reservation.checkOut)}</dd>
        <dt>Belegung</dt>
        <dd>{reservation.guests} {reservation.guests === 1 ? 'Gast' : 'Gäste'}, {reservation.rooms} Zimmer</dd>
        <dt>Gebucht für</dt>
        <dd>{holder.firstName} {holder.lastName}</dd>
        <dt>Preis</dt>
        <dd>{money(reservation.price, reservation.currency)} <span className="va-small va-muted">inkl. {money(reservation.fee, reservation.currency)} Servicegebühr</span></dd>
        <dt>Storno</dt>
        <dd>
          {reservation.refundable
            ? reservation.freeCancellationUntil ? `kostenlos bis ${formatLocalDateTime(reservation.freeCancellationUntil)}, Geld zurück per PayPal` : 'kostenlos, Geld zurück per PayPal'
            : 'nicht stornierbar'}
        </dd>
      </dl>
      <RouteHint reservationId={reservation.id} />
      <ul className="va-plain-list va-paypal-promise">
        <li><CheckOutlined aria-hidden="true" /> Bezahlen ohne Kartendaten, du bleibst in der App.</li>
        <li><CheckOutlined aria-hidden="true" /> PayPal reserviert den Betrag nur. Abgebucht wird erst, wenn das Hotel bestätigt hat.</li>
        <li><CheckOutlined aria-hidden="true" /> Klappt die Buchung nicht, wird die Reservierung sofort freigegeben.</li>
      </ul>
    </div>
  );
}

/**
 * Before paying: if the hotel lies in another place, the stop moves there after booking. Shows the arrival on the way
 * to it and warns if it is after the check-in or passes a closure; booking stays possible. Nothing within the same place.
 */
function RouteHint({ reservationId }: { reservationId: number }) {
  const [check, setCheck] = useState<RouteCheck | null>(null);
  useEffect(() => {
    let active = true;
    fetchRouteCheck(reservationId).then((result) => active && setCheck(result));
    return () => {
      active = false;
    };
  }, [reservationId]);
  const text = routeCheckText(check);
  if (!text) {
    return null;
  }
  return (
    <div className="va-note">
      <p className="va-strong"><NodeIndexOutlined aria-hidden="true" /> Anderer Ort</p>
      <p>{text.move}</p>
      {text.arrival && <p>{text.arrival}</p>}
      {text.warning && <Alert type="warning" showIcon message={text.warning} />}
    </div>
  );
}

/** While booking after the approval at PayPal: the three steps, the last ones still running. */
export function BookingProgress({ reservation }: { reservation: Reservation }) {
  return (
    <div className="va-form" role="status" aria-live="polite">
      <div className="va-impact">
        <span className="va-eyebrow">Wird gebucht</span>
        <span className="va-strong">{reservation.hotelName}</span>
      </div>
      <ol className="va-plain-list va-progress">
        <li className="va-progress-done"><CheckOutlined aria-hidden="true" /> Preis gesichert</li>
        <li className="va-progress-done"><CheckOutlined aria-hidden="true" /> Zahlung bei PayPal freigegeben</li>
        <li><LoadingOutlined aria-hidden="true" /> Hotel bestätigt die Buchung, danach bucht PayPal {money(reservation.price, reservation.currency)} ab</li>
      </ol>
      <p className="va-small va-muted">Das dauert wenige Sekunden. Du kannst die App schließen, die Buchung läuft weiter.</p>
    </div>
  );
}

function Booked({ reservation, oldTitle, oldManageUrl, oldProvider, oldMarked, onMarkOld }: {
  reservation: Reservation;
  oldTitle: string;
  oldManageUrl: string | null;
  oldProvider: string | null;
  oldMarked: boolean;
  onMarkOld: () => void;
}) {
  const place = reservation.booking;
  return (
    <div className="va-form">
      <div className="va-success">
        <CheckCircleOutlined aria-hidden="true" />
        <span className="va-column">
          <span className="va-strong">{reservation.hotelName}</span>
          <span className="va-small">{date(reservation.checkIn)} – {date(reservation.checkOut)}</span>
        </span>
      </div>
      <dl className="va-facts">
        {place?.reference && (
          <>
            <dt>Buchungsnr.</dt>
            <dd className="va-mono">{place.reference}</dd>
          </>
        )}
        <dt>Bezahlt</dt>
        <dd>{money(reservation.price, reservation.currency)} mit PayPal</dd>
        {reservation.refundable && (
          <>
            <dt>Storno</dt>
            <dd>
              {reservation.freeCancellationUntil ? `kostenlos bis ${formatLocalDateTime(reservation.freeCancellationUntil)}` : 'kostenlos'},
              {' '}in der Buchung, Geld kommt automatisch per PayPal zurück
            </dd>
          </>
        )}
      </dl>
      {place?.latitude != null && place.longitude != null && (
        <a className="va-button va-button-accent va-block" href={directionsUrl(place.latitude, place.longitude)}
          target="_blank" rel="noopener noreferrer">
          <EnvironmentOutlined aria-hidden="true" /> Route zur Unterkunft
        </a>
      )}
      {reservation.replannedRouteIds.length > 0 && (
        <ReplannedRoutes stopId={reservation.stopId} routeIds={reservation.replannedRouteIds} />
      )}
      {!oldMarked ? (
        <div className="va-note">
          <p>„{oldTitle}“ brauchst du nicht mehr? Sie steht auf deiner To-do-Liste der Reise. Markiere sie als storniert{oldManageUrl ? ' und storniere sie beim Anbieter' : ''}.</p>

          <div className="va-row va-gap">
            <button type="button" className="va-button va-button-outline" onClick={onMarkOld}>Als storniert markieren</button>
            {oldManageUrl && (
              <a className="va-button va-button-outline" href={oldManageUrl} target="_blank" rel="noopener noreferrer">
                {oldProvider ? `Bei ${oldProvider} stornieren` : 'Beim Anbieter stornieren'}
              </a>
            )}
          </div>
        </div>
      ) : (
        <p className="va-small va-muted">„{oldTitle}“ ist als storniert markiert.</p>
      )}
    </div>
  );
}

/**
 * After booking in another place: the stop moved there and its routes were planned again on the fastest way. Where
 * more than one way comes into question the user can choose (RouteSheet), otherwise nothing is asked.
 */
export function ReplannedRoutes({ stopId, routeIds }: { stopId: number; routeIds: number[] }) {
  const { trip, stopById } = useTripContext();
  const stop = stopById.get(stopId);
  const [choices, setChoices] = useState<number[]>([]);
  const routes = trip.routes.filter((route) => routeIds.includes(route.id));
  const routeKey = routes.map((route) => `${route.id}:${route.fromStopId}`).join(',');

  useEffect(() => {
    let active = true;
    Promise.all(routes.map((route) => fetchSuggestions(route.id, {
      includeWaypoints: false,
      departureAt: substituteDeparture(trip, stopById.get(route.fromStopId)),
    }).then((result) => (result.options.length > 1 ? route.id : null), () => null))).then((ids) => {
      if (active) {
        setChoices(ids.filter((id): id is number => id != null));
      }
    });
    return () => {
      active = false;
    };
  }, [routeKey]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="va-note">
      <p>
        <NodeIndexOutlined aria-hidden="true" /> Der Stop liegt jetzt in {stop?.name ?? 'dem Ort der Unterkunft'}. Die
        Route{routes.length > 1 ? 'n' : ''} dorthin und weiter {routes.length > 1 ? 'wurden' : 'wurde'} neu geplant.
      </p>
      {choices.length > 0 && (
        <div className="va-column va-gap">
          <span className="va-small va-muted">Es gibt mehrere sinnvolle Wege, wähle deinen:</span>
          {routes.filter((route) => choices.includes(route.id)).map((route) => (
            <Link key={route.id} className="va-button va-button-outline va-block"
              to={`/reisen/${trip.id}/routen/${route.id}`}>
              Route {routeName(route, stopById)} wählen
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

function criteriaSummary(criteria: Criteria): string {
  const guests = criteria.guests ?? 0;
  return [
    criteria.placeName ? `${(criteria.radiusMeters / 1000).toLocaleString('de-DE')} km um ${criteria.placeName}` : null,
    criteria.checkIn && criteria.checkOut ? `${date(criteria.checkIn)} – ${date(criteria.checkOut)}` : null,
    `${guests} ${guests === 1 ? 'Gast' : 'Gäste'}`,
    criteria.maxPrice ? `bis ${money(criteria.maxPrice.replace(',', '.'), criteria.currency)}` : null,
  ].filter(Boolean).join(' · ');
}

function canSearch(criteria: Criteria): boolean {
  return criteria.latitude != null && !!criteria.checkIn && !!criteria.checkOut && criteria.checkOut > criteria.checkIn
    && criteria.guests != null && criteria.guests >= criteria.rooms && /^[A-Z]{3}$/.test(criteria.currency)
    && (!criteria.maxPrice.trim() || Number(criteria.maxPrice.replace(',', '.')) > 0);
}

export function holderComplete(holder: Holder): boolean {
  return !!holder.firstName.trim() && !!holder.lastName.trim() && /\S+@\S+\.\S+/.test(holder.email)
    && /^\+?[0-9 ()/-]{6,30}$/.test(holder.phone.trim());
}

export function money(amount: string, currency: string): string {
  try {
    return new Intl.NumberFormat('de-DE', { style: 'currency', currency }).format(Number(amount));
  } catch {
    return `${amount} ${currency}`;
  }
}

function distance(meters: number): string {
  return meters < 1000 ? `${Math.round(meters / 10) * 10} m` : `${(meters / 1000).toLocaleString('de-DE', { maximumFractionDigits: 1 })} km`;
}

export function date(value: string): string {
  const [year, month, day] = value.split('-');
  return `${day}.${month}.${year}`;
}

export function failureText(reservation: Reservation): string {
  return reservation.status === 'FAILED'
    ? 'Die Buchung hat nicht geklappt, es wurde nichts abgebucht. Bitte wähle eine andere Unterkunft.'
    : 'Die Buchung ist noch nicht abgeschlossen.';
}

export function errorText(failure: unknown): string {
  const code = failure instanceof TripsApiError ? failure.code : undefined;
  switch (code) {
    case 'OFFER_UNAVAILABLE':
      return 'Das Angebot ist nicht mehr verfügbar. Es wurde nichts abgebucht, bitte suche erneut.';
    case 'PRICE_EXCEEDED':
      return 'Der Preis ist inzwischen höher als dein Maximum. Bitte wähle ein anderes Angebot.';
    case 'PAYMENT_NOT_APPROVED':
      return 'Die Zahlung wurde bei PayPal noch nicht freigegeben.';
    case 'PAYMENT_DECLINED':
      return 'PayPal hat die Zahlung abgelehnt. Es wurde nichts gebucht.';
    case 'ALTERNATIVES_RATE_LIMITED':
      return 'Zu viele Suchen in kurzer Zeit. Bitte versuche es später noch einmal.';
    case 'ALTERNATIVES_UNAVAILABLE':
      return 'Der Buchungsdienst antwortet gerade nicht. Eine laufende Buchung schließen wir automatisch ab, sonst versuche es gleich noch einmal.';
    case 'PAYPAL_NOT_CONFIGURED':
    case 'PAYPAL_ERROR':
      return 'PayPal ist gerade nicht erreichbar.';
    case 'INVALID_BOOKING_INPUT':
      return 'Bitte prüfe die Angaben.';
    default:
      return 'Das hat nicht geklappt. Bitte versuche es noch einmal.';
  }
}
