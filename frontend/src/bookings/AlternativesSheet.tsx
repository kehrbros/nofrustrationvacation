import { CheckCircleOutlined, CheckOutlined, EnvironmentOutlined, NodeIndexOutlined, SearchOutlined, StarFilled } from '@ant-design/icons';
import { Alert, Spin } from 'antd';
import { useEffect, useRef, useState, type InputHTMLAttributes } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';

import KiwiLoader from '../components/KiwiLoader';
import Sheet from '../components/Sheet';
import { formatMoney, formatNumber, t } from '../i18n';
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
import { dateProblem, earliestCheckOut, fromEarliest } from './stayDates';
import { failedHolderField, holderComplete, useHolder, type HolderField, type HolderState } from './holder';
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
  const guest = useHolder(trip, reload);
  const holder = guest.holder;
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
        () => setError(t('payment.cancelledAtPayPal')),
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
      fetchCriteria(booking.id).then(setCriteria, () => setError(t('alternatives.criteriaFailed')));
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
      <Sheet open onClose={close} title={t('booking.notFound')}>
        <p className="va-muted">{t('booking.gone')}</p>
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
      guest.saved();
      setStep({ kind: 'pay', reservation, result });
    } catch (failure) {
      setError(errorText(failure));
      setStep(guest.refused(failure) ? { kind: 'holder', result, candidate } : { kind: 'results', result });
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
      setError(t('alternatives.markFailed'));
    }
  };

  const title = step.kind === 'booked' ? t('alternatives.rebooked') : step.kind === 'attention' ? t('payment.bookedChecking')
    : t('tripAlternatives.otherStay');
  return (
    <Sheet open onClose={close} eyebrow={t('alternatives.insteadOf', { title: booking.title })} title={title} height="90vh"
      footer={footer()}>
      {status && !status.searchAvailable && step.kind === 'criteria' && (
        <Alert type="info" showIcon message={t('alternatives.unavailable')} />
      )}
      {error && <Alert type="error" showIcon message={error} closable onClose={() => setError(null)} />}
      {notice && <Alert type="info" showIcon message={notice} closable onClose={() => setNotice(null)} />}
      {step.kind === 'criteria' && (criteria
        ? <CriteriaForm criteria={criteria} onChange={setCriteria} />
        : <div className="va-empty"><Spin /></div>)}
      {step.kind === 'searching' && (
        <div className="va-empty va-column va-gap"><Spin /><span className="va-muted">{t('alternatives.searching')}</span></div>
      )}
      {step.kind === 'results' && (
        <Results result={step.result} criteria={criteria!} onPick={(candidate) => pick(candidate, step.result)} />
      )}
      {step.kind === 'holder' && (
        <HolderForm candidate={step.candidate} state={guest} />
      )}
      {step.kind === 'reserving' && (
        <div className="va-empty va-column va-gap"><Spin /><span className="va-muted">{t('payment.securingPrice', { hotel: step.candidate.name })}</span></div>
      )}
      {step.kind === 'pay' && <PaySummary reservation={step.reservation} holder={holder} />}
      {step.kind === 'booking' && <BookingProgress reservation={step.reservation} />}
      {step.kind === 'returned' && (
        <div className="va-empty va-column va-gap"><Spin /><span className="va-muted">{t('payment.checkingAndBooking')}</span></div>
      )}
      {step.kind === 'booked' && (
        <Booked reservation={step.reservation} oldTitle={booking.title} oldManageUrl={booking.manageUrl}
          oldProvider={booking.provider} oldMarked={booking.status === 'CANCELLED' || oldMarked} onMarkOld={() => void markOld()} />
      )}
      {step.kind === 'attention' && (
        <div className="va-form">
          <p className="va-strong"><CheckCircleOutlined aria-hidden="true" /> {t('alternatives.isBooked', { hotel: step.reservation.hotelName })}</p>
          <p className="va-muted">{t('alternatives.attention')}</p>
        </div>
      )}
    </Sheet>
  );

  function footer() {
    switch (step.kind) {
      case 'criteria':
        return (
          <div className="va-row va-gap">
            <button type="button" className="va-button va-button-outline" onClick={back}>{t('alternatives.back')}</button>
            <button type="button" className="va-button va-grow" disabled={busy || !criteria || !canSearch(criteria) || !status?.searchAvailable}
              onClick={() => void search()}>
              {busy ? <KiwiLoader size="small" decorative /> : <SearchOutlined aria-hidden="true" />} {busy ? t('alternatives.searchRunning') : t('alternatives.search')}
            </button>
          </div>
        );
      case 'results':
        return (
          <button type="button" className="va-button va-button-outline va-block" onClick={() => setStep({ kind: 'criteria' })}>
            {t('alternatives.adjust')}
          </button>
        );
      case 'holder':
        return (
          <div className="va-row va-gap">
            <button type="button" className="va-button va-button-outline" onClick={() => setStep({ kind: 'results', result: step.result })}>
              {t('alternatives.back')}
            </button>
            <button type="button" className="va-button va-grow" disabled={busy || !status?.bookingAvailable}
              onClick={() => guest.check() && void reserve(step.candidate, step.result)}>
              {t('payment.continue')}
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
              onCancel={() => setNotice(t('payment.cancelled'))}
              onError={() => setError(t('payment.startFailed'))}
            />
            {!step.reservation.payPalOrderId && !step.reservation.approveUrl && (
              <button type="button" className="va-button va-block" onClick={() => book(step.reservation)}>
                {t('common.retry')}
              </button>
            )}
            {step.result && (
              <button type="button" className="va-text-button" onClick={() => {
                void abortReservation(step.reservation.id).catch(() => undefined);
                setStep({ kind: 'results', result: step.result! });
              }}>
                {t('alternatives.chooseOther')}
              </button>
            )}
          </div>
        );
      case 'booked':
        return (
          <button type="button" className="va-button va-block"
            onClick={() => navigate(`/reisen/${trip.id}/buchungen/${step.reservation.bookingId}`)}>
            {t('alternatives.viewNew')}
          </button>
        );
      case 'attention':
        return (
          <button type="button" className="va-button va-block" onClick={close}>{t('payment.toTrip')}</button>
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
  const problem = dateProblem(criteria);
  return (
    <div className="va-form">
      <p className="va-small va-muted">
        {t('alternatives.criteriaIntro')}
      </p>
      <div className={mark('location')}>
        <span className="va-label">{t('alternatives.region')}</span>
        {!changingPlace ? (
          <span className="va-row va-gap va-baseline">
            <span className="va-muted va-grow"><EnvironmentOutlined aria-hidden="true" /> {criteria.placeName ?? t('stop.placeSelected')}</span>
            <button type="button" className="va-text-button" onClick={() => setChangingPlace(true)}>{t('alternatives.change')}</button>
          </span>
        ) : (
          <>
            <input type="search" autoComplete="off" aria-label={t('alternatives.placeSearch')} placeholder={t('alternatives.placePlaceholder')}
              value={placeQuery} onChange={(event) => setPlaceQuery(event.target.value)} />
            {places.length > 0 && (
              <ul className="va-suggestions" aria-label={t('stop.suggestions')}>
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
        <label htmlFor="alt-radius">{t('alternatives.radius', { km: formatNumber(criteria.radiusMeters / 1000) })}</label>
        <input id="alt-radius" type="range" min={500} max={20000} step={500} value={criteria.radiusMeters}
          onChange={(event) => set({ radiusMeters: Number(event.target.value) })} />
      </div>
      <div className="va-field">
        <label htmlFor="alt-stars">{t('alternatives.minStars')}</label>
        <select id="alt-stars" value={criteria.minStars ?? ''}
          onChange={(event) => set({ minStars: event.target.value ? Number(event.target.value) : null })}>
          <option value="">{t('alternatives.anyStars')}</option>
          {[2, 3, 4, 5].map((stars) => <option key={stars} value={stars}>{t('alternatives.fromStars', { stars })}</option>)}
        </select>
      </div>
      <div className="va-field-row">
        <div className={problem ? 'va-field va-field-invalid' : mark('checkIn')}>
          <label htmlFor="alt-in">{t('booking.checkIn')}</label>
          <input id="alt-in" type="date" min={criteria.earliestCheckIn || undefined} value={criteria.checkIn}
            aria-invalid={problem ? true : undefined} aria-describedby={problem ? 'alt-dates-error' : undefined}
            onChange={(event) => set({ checkIn: event.target.value })} />
        </div>
        <div className={problem === 'over' ? 'va-field va-field-invalid' : mark('checkOut')}>
          <label htmlFor="alt-out">{t('booking.checkOut')}</label>
          <input id="alt-out" type="date" min={earliestCheckOut(criteria)} value={criteria.checkOut}
            aria-invalid={problem === 'over' ? true : undefined} aria-describedby={problem ? 'alt-dates-error' : undefined}
            onChange={(event) => set({ checkOut: event.target.value })} />
        </div>
      </div>
      {problem && (
        <div className="va-column va-gap">
          <p id="alt-dates-error" className="va-field-error" role="alert">
            {t(problem === 'over' ? 'alternatives.datesOver' : 'alternatives.datesStartEarlier', { date: date(criteria.earliestCheckIn) })}
          </p>
          {problem === 'startsEarlier' && (
            <button type="button" className="va-text-button va-dates-fix" onClick={() => onChange(fromEarliest(criteria))}>
              {t('alternatives.searchFromEarliest', { date: date(criteria.earliestCheckIn) })}
            </button>
          )}
        </div>
      )}
      <div className="va-field-row">
        <div className={mark('guests')}>
          <label htmlFor="alt-guests">{t('form.guests')}</label>
          <input id="alt-guests" type="number" min={1} max={36} inputMode="numeric" value={criteria.guests ?? ''}
            onChange={(event) => set({ guests: event.target.value ? Number(event.target.value) : null })} />
        </div>
        <div className="va-field">
          <label htmlFor="alt-rooms">{t('form.rooms')}</label>
          <input id="alt-rooms" type="number" min={1} max={9} inputMode="numeric" value={criteria.rooms}
            onChange={(event) => set({ rooms: Math.max(1, Number(event.target.value) || 1) })} />
        </div>
      </div>
      <div className="va-field-row">
        <div className="va-field">
          <label htmlFor="alt-price">{t('alternatives.maxPrice')}</label>
          <input id="alt-price" type="text" inputMode="decimal" placeholder={t('alternatives.noLimit')} value={criteria.maxPrice}
            onChange={(event) => set({ maxPrice: event.target.value })} />
        </div>
        <div className="va-field">
          <label htmlFor="alt-currency">{t('accommodation.currency')}</label>
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
        <p className="va-strong">{t('alternatives.none')}</p>
        <p className="va-muted">
          {result.offersChecked > 0
            ? t('alternatives.offersChecked', { count: result.offersChecked })
            : t('alternatives.noRooms')}
        </p>
        {result.excludedByAssistant > 0 && <p className="va-small va-muted">{excludedHint(result.excludedByAssistant)}</p>}
      </div>
    );
  }
  return (
    <div className="va-form">
      <p className="va-small va-muted">
        {t('alternatives.found', { count: result.candidates.length, criteria: criteriaSummary(criteria) })}{' '}
        {result.ranked ? t('alternatives.bestFirst') : t('alternatives.cheapestFirst')}
        {result.excludedByAssistant > 0 && <> {excludedHint(result.excludedByAssistant)}</>}
      </p>
      <ul className="va-plain-list va-alternatives">
        {result.candidates.map((candidate) => (
          <li key={candidate.hotelId}>
            <button type="button" className={`va-option va-alternative ${candidate.hotelId === result.recommendedHotelId ? 'va-option-selected' : ''}`}
              onClick={() => onPick(candidate)}>
              {candidate.photoUrl && <img src={candidate.photoUrl} alt="" className="va-alternative-photo" loading="lazy" />}
              <span>
                {candidate.hotelId === result.recommendedHotelId && <span className="va-chip va-chip-ink">{t('alternatives.recommendation')}</span>}
                <span className="va-option-title">{candidate.name}</span>
                <span className="va-small va-muted">
                  {[candidate.stars ? <span key="s">{candidate.stars} <StarFilled aria-label={t('alternatives.stars')} /></span> : null,
                    distance(candidate.distanceMeters), candidate.boardName].filter(Boolean)
                    .map((part, index) => <span key={index}>{index > 0 && ' · '}{part}</span>)}
                </span>
                <span className="va-strong">{money(candidate.price, candidate.currency)}</span>
                <span className="va-small">
                  {candidate.refundable
                    ? candidate.freeCancellationUntil
                      ? t('alternatives.freeUntil', { time: formatLocalDateTime(candidate.freeCancellationUntil) })
                      : t('alternatives.free')
                    : t('alternatives.nonRefundable')}
                </span>
                {candidate.possiblySame && <span className="va-small va-check-hint">{t('alternatives.possiblySame')}</span>}
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

export function HolderForm({ candidate, state }: { candidate: HolderOffer; state: HolderState }) {
  const { holder, errors } = state;
  const set = (patch: Partial<Holder>) => state.setHolder({ ...holder, ...patch });
  const field = (name: HolderField, label: string, input: Omit<InputHTMLAttributes<HTMLInputElement>, 'id' | 'value'>) => {
    const id = `alt-${name}`;
    const error = errors[name];
    return (
      <div className={error ? 'va-field va-field-invalid' : 'va-field'}>
        <label htmlFor={id}>{label}</label>
        <input id={id} value={holder[name]} aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${id}-error` : undefined}
          onChange={(event) => set({ [name]: event.target.value })} onBlur={() => state.touch(name)} {...input} />
        {error && <p id={`${id}-error`} className="va-field-error" role="alert">{error}</p>}
      </div>
    );
  };
  return (
    <div className="va-form">
      <div className="va-impact">
        <span className="va-eyebrow">{candidate.roomName ?? t('addBooking.accommodation')}</span>
        <span className="va-strong">{candidate.name} · {money(candidate.price, candidate.currency)}</span>
      </div>
      <p className="va-small va-muted">
        {t('alternatives.holderIntro')}
      </p>
      <div className="va-field-row">
        {field('firstName', t('alternatives.firstName'), { autoComplete: 'given-name' })}
        {field('lastName', t('alternatives.lastName'), { autoComplete: 'family-name' })}
      </div>
      {field('email', t('booking.email'), { type: 'email', autoComplete: 'email', inputMode: 'email' })}
      {field('phone', t('booking.phone'), { type: 'tel', autoComplete: 'tel', inputMode: 'tel' })}
      <label className="va-row va-gap va-remember">
        <input type="checkbox" checked={state.rememberForTrip} onChange={(event) => state.setRememberForTrip(event.target.checked)} />
        <span className="va-column">
          <span className="va-strong">{t('alternatives.rememberForTrip')}</span>
          <span className="va-small va-muted">{t('alternatives.rememberForTripHint')}</span>
        </span>
      </label>
    </div>
  );
}

export function PaySummary({ reservation, holder }: { reservation: Reservation; holder: Holder }) {
  return (
    <div className="va-form">
      <div className="va-impact">
        <span className="va-eyebrow">{t('payment.priceSecured')}</span>
        <span className="va-strong">{reservation.hotelName}</span>
        <span className="va-strong">{money(reservation.price, reservation.currency)}</span>
      </div>
      <dl className="va-facts">
        <dt>{t('booking.stay')}</dt>
        <dd>{date(reservation.checkIn)} – {date(reservation.checkOut)}</dd>
        <dt>{t('booking.occupancy')}</dt>
        <dd>{t('common.guests', { count: reservation.guests })}, {t('common.rooms', { count: reservation.rooms })}</dd>
        <dt>{t('payment.bookedFor')}</dt>
        <dd>{holder.firstName} {holder.lastName}</dd>
        <dt>{t('booking.price')}</dt>
        <dd>
          {money(reservation.price, reservation.currency)}{' '}
          <span className="va-small va-muted">{t('payment.fee', { fee: money(reservation.fee, reservation.currency) })}</span>
        </dd>
        <dt>{t('booking.cancellation')}</dt>
        <dd>
          {reservation.refundable
            ? reservation.freeCancellationUntil
              ? t('payment.freeUntilRefund', { time: formatLocalDateTime(reservation.freeCancellationUntil) })
              : t('payment.freeRefund')
            : t('proposal.nonRefundable')}
        </dd>
      </dl>
      <RouteHint reservationId={reservation.id} />
      <ul className="va-plain-list va-paypal-promise">
        <li><CheckOutlined aria-hidden="true" /> {t('payment.promiseNoCard')}</li>
        <li><CheckOutlined aria-hidden="true" /> {t('payment.promiseReserved')}</li>
        <li><CheckOutlined aria-hidden="true" /> {t('payment.promiseReleased')}</li>
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
      <p className="va-strong"><NodeIndexOutlined aria-hidden="true" /> {t('payment.otherPlace')}</p>
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
        <span className="va-eyebrow">{t('payment.booking')}</span>
        <span className="va-strong">{reservation.hotelName}</span>
      </div>
      <ol className="va-plain-list va-progress">
        <li className="va-progress-done"><CheckOutlined aria-hidden="true" /> {t('payment.priceSecured')}</li>
        <li className="va-progress-done"><CheckOutlined aria-hidden="true" /> {t('payment.approved')}</li>
        <li>
          <KiwiLoader size="small" decorative />{' '}
          {t('payment.hotelConfirms', { amount: money(reservation.price, reservation.currency) })}
        </li>
      </ol>
      <p className="va-small va-muted">{t('payment.fewSeconds')}</p>
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
            <dt>{t('booking.reference')}</dt>
            <dd className="va-mono">{place.reference}</dd>
          </>
        )}
        <dt>{t('payment.paid')}</dt>
        <dd>{t('payment.withPayPal', { amount: money(reservation.price, reservation.currency) })}</dd>
        {reservation.refundable && (
          <>
            <dt>{t('booking.cancellation')}</dt>
            <dd>
              {reservation.freeCancellationUntil
                ? t('payment.bookedFreeUntil', { time: formatLocalDateTime(reservation.freeCancellationUntil) })
                : t('payment.bookedFree')}
            </dd>
          </>
        )}
      </dl>
      {place?.latitude != null && place.longitude != null && (
        <a className="va-button va-button-accent va-block" href={directionsUrl(place.latitude, place.longitude)}
          target="_blank" rel="noopener noreferrer">
          <EnvironmentOutlined aria-hidden="true" /> {t('payment.directions')}
        </a>
      )}
      {reservation.replannedRouteIds.length > 0 && (
        <ReplannedRoutes stopId={reservation.stopId} routeIds={reservation.replannedRouteIds} />
      )}
      {!oldMarked ? (
        <div className="va-note">
          <p>{oldManageUrl ? t('alternatives.oldWithProvider', { title: oldTitle }) : t('alternatives.old', { title: oldTitle })}</p>

          <div className="va-row va-gap">
            <button type="button" className="va-button va-button-outline" onClick={onMarkOld}>{t('alternatives.markCancelled')}</button>
            {oldManageUrl && (
              <a className="va-button va-button-outline" href={oldManageUrl} target="_blank" rel="noopener noreferrer">
                {oldProvider ? t('todo.cancelAt', { provider: oldProvider }) : t('alternatives.cancelAtProvider')}
              </a>
            )}
          </div>
        </div>
      ) : (
        <p className="va-small va-muted">{t('alternatives.marked', { title: oldTitle })}</p>
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
        <NodeIndexOutlined aria-hidden="true" />{' '}
        {stop ? t('alternatives.stopMoved', { name: stop.name }) : t('alternatives.stopMovedPlace')}{' '}
        {t('alternatives.routesReplanned', { count: routes.length })}
      </p>
      {choices.length > 0 && (
        <div className="va-column va-gap">
          <span className="va-small va-muted">{t('alternatives.severalWays')}</span>
          {routes.filter((route) => choices.includes(route.id)).map((route) => (
            <Link key={route.id} className="va-button va-button-outline va-block"
              to={`/reisen/${trip.id}/routen/${route.id}`}>
              {t('alternatives.chooseRoute', { route: routeName(route, stopById) })}
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
    criteria.placeName
      ? t('alternatives.around', { km: formatNumber(criteria.radiusMeters / 1000), place: criteria.placeName })
      : null,
    criteria.checkIn && criteria.checkOut ? `${date(criteria.checkIn)} – ${date(criteria.checkOut)}` : null,
    t('common.guests', { count: guests }),
    criteria.minStars ? t('alternatives.fromStars', { stars: criteria.minStars }) : null,
    criteria.maxPrice ? t('alternatives.upTo', { price: money(criteria.maxPrice.replace(',', '.'), criteria.currency) }) : null,
  ].filter(Boolean).join(' · ');
}

/** The assistant left out hotels it found not comparable (other kind or clearly lower standard). */
function excludedHint(count: number): string {
  return t('alternatives.excluded', { count });
}

function canSearch(criteria: Criteria): boolean {
  return criteria.latitude != null && !!criteria.checkIn && !!criteria.checkOut && criteria.checkOut > criteria.checkIn
    && dateProblem(criteria) === null
    && criteria.guests != null && criteria.guests >= criteria.rooms && /^[A-Z]{3}$/.test(criteria.currency)
    && (!criteria.maxPrice.trim() || Number(criteria.maxPrice.replace(',', '.')) > 0);
}

export const money = formatMoney;

function distance(meters: number): string {
  return meters < 1000 ? `${Math.round(meters / 10) * 10} m` : `${formatNumber(meters / 1000, { maximumFractionDigits: 1 })} km`;
}

/** "12.07.2027" (German) or "12/07/2027" (English) from yyyy-mm-dd */
export function date(value: string): string {
  const [year, month, day] = value.split('-');
  return t('alternatives.date', { day, month, year });
}

export function failureText(reservation: Reservation): string {
  return reservation.status === 'FAILED'
    ? t('payment.failed')
    : t('payment.notFinished');
}

export function errorText(failure: unknown): string {
  const code = failure instanceof TripsApiError ? failure.code : undefined;
  switch (code) {
    case 'OFFER_UNAVAILABLE':
      return t('payment.error.OFFER_UNAVAILABLE');
    case 'PRICE_EXCEEDED':
      return t('payment.error.PRICE_EXCEEDED');
    case 'PAYMENT_NOT_APPROVED':
      return t('payment.error.PAYMENT_NOT_APPROVED');
    case 'PAYMENT_DECLINED':
      return t('payment.error.PAYMENT_DECLINED');
    case 'ALTERNATIVES_RATE_LIMITED':
      return t('payment.error.ALTERNATIVES_RATE_LIMITED');
    case 'ALTERNATIVES_UNAVAILABLE':
      return t('payment.error.ALTERNATIVES_UNAVAILABLE');
    case 'PAYPAL_NOT_CONFIGURED':
    case 'PAYPAL_ERROR':
      return t('payment.error.PAYPAL_ERROR');
    case 'INVALID_BOOKING_INPUT':
    case 'INVALID_TRIP_INPUT':
      return failedHolderField(failure) ? t('payment.error.INVALID_GUEST') : t('payment.error.INVALID_BOOKING_INPUT');
    default:
      return t('common.failedTryAgain');
  }
}
