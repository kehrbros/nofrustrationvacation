import { CheckCircleOutlined, EnvironmentOutlined } from '@ant-design/icons';
import { Alert, Spin } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router';

import Sheet from '../components/Sheet';
import PayPalButton from '../payments/PayPalButton';
import type { ProposalStay } from '../proposals/proposalsApi';
import { useTripContext } from '../trips/tripContext';
import {
  BookingProgress,
  date,
  errorText,
  failureText,
  HolderForm,
  holderComplete,
  money,
  PaySummary,
} from './AlternativesSheet';
import {
  abortReservation,
  bookReservation,
  fetchAlternativesStatus,
  fetchReservation,
  rememberedHolder,
  rememberHolder,
  reserveStay,
  type AlternativesStatus,
  type Holder,
  type Reservation,
} from './alternativesApi';
import { directionsUrl } from './text';

/** What the page needs to book a hotel of a proposal, handed over by the proposals dialog. */
export interface StayBookingState {
  stay: ProposalStay;
}

type Step =
  | { kind: 'holder' }
  | { kind: 'reserving' }
  | { kind: 'pay'; reservation: Reservation }
  | { kind: 'booking'; reservation: Reservation }
  | { kind: 'returned' }
  | { kind: 'booked'; reservation: Reservation }
  | { kind: 'attention'; reservation: Reservation }
  | { kind: 'gone' };

const EMPTY_HOLDER: Holder = { firstName: '', lastName: '', email: '', phone: '' };

/**
 * Books the hotel of a taken proposal at its stop: a night on the way (new stop) or another accommodation at the
 * target stop, paid with the PayPal button like an alternative (Docs/accommodation-alternatives.md#zahlung). The offer
 * comes from the proposal, the price is checked again before paying. The redirect fallback of PayPal comes back here.
 */
export default function StayBookingSheet() {
  const { stopId } = useParams();
  const [params] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const { trip, stopById, reload, close } = useTripContext();
  const stop = stopById.get(Number(stopId));
  const stay = (location.state as StayBookingState | null)?.stay ?? null;
  const returnedReservation = params.get('reservierung');
  const cancelled = params.get('abgebrochen') === '1';

  const [status, setStatus] = useState<AlternativesStatus | null>(null);
  const [holder, setHolder] = useState<Holder>(() => rememberedHolder() ?? EMPTY_HOLDER);
  const [step, setStep] = useState<Step>(returnedReservation ? { kind: 'returned' } : stay ? { kind: 'holder' } : { kind: 'gone' });
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const completing = useRef(false);
  const autoReserved = useRef(false);

  useEffect(() => {
    fetchAlternativesStatus().then(setStatus, () => undefined);
  }, []);

  const finished = (reservation: Reservation) => {
    if (reservation.status === 'BOOKED') {
      reload();
      setStep({ kind: 'booked', reservation });
    } else if (reservation.status === 'NEEDS_ATTENTION' || reservation.status === 'PROVIDER_BOOKED') {
      reload();
      setStep({ kind: 'attention', reservation });
    } else if (reservation.status === 'FAILED') {
      setError(failureText(reservation));
      setStep(stay ? { kind: 'holder' } : { kind: 'gone' });
    } else {
      setStep({ kind: 'pay', reservation });
    }
  };

  const book = (reservation: Reservation) => {
    setError(null);
    setNotice(null);
    setStep({ kind: 'booking', reservation });
    bookReservation(reservation.id).then(finished, (failure) => {
      setError(errorText(failure));
      fetchReservation(reservation.id).then(finished, () => setStep({ kind: 'pay', reservation }));
    });
  };

  const reserve = async () => {
    if (!stay || !stop) return;
    setError(null);
    setStep({ kind: 'reserving' });
    try {
      const reservation = await reserveStay(stop.id, stay.replacesBookingId, stay, holder);
      rememberHolder(holder);
      setStep({ kind: 'pay', reservation });
    } catch (failure) {
      setError(errorText(failure));
      setStep({ kind: 'holder' });
    }
  };

  // guest data known from the last booking: lock the price right away
  useEffect(() => {
    if (!autoReserved.current && step.kind === 'holder' && stay && stop && holderComplete(holder) && status?.bookingAvailable) {
      autoReserved.current = true;
      void reserve();
    }
  }, [step, status]); // eslint-disable-line react-hooks/exhaustive-deps

  // back from PayPal after the redirect fallback
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
      ).finally(() => setStep({ kind: 'gone' }));
      return;
    }
    bookReservation(id).then(finished, (failure) => {
      setError(errorText(failure));
      fetchReservation(id).then(finished, () => setStep({ kind: 'gone' }));
    });
  }, [returnedReservation, cancelled]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!stop) {
    return (
      <Sheet open onClose={close} title="Stop nicht gefunden">
        <p className="va-muted">Diesen Stop gibt es nicht mehr.</p>
      </Sheet>
    );
  }

  const title = step.kind === 'booked' ? 'Gebucht' : step.kind === 'attention' ? 'Gebucht, Zahlung wird geprüft'
    : stay?.hotelName ?? 'Unterkunft buchen';
  return (
    <Sheet open onClose={close} eyebrow={`Unterkunft in ${stop.name}`} title={title} height="90vh" footer={footer()}>
      {status && !status.bookingAvailable && step.kind === 'holder' && (
        <Alert type="info" showIcon message="Buchen ist gerade nicht möglich. Du kannst die Unterkunft auch selbst buchen." />
      )}
      {error && <Alert type="error" showIcon message={error} closable onClose={() => setError(null)} />}
      {notice && <Alert type="info" showIcon message={notice} closable onClose={() => setNotice(null)} />}
      {step.kind === 'holder' && stay && (
        <>
          <dl className="va-facts">
            <dt>Aufenthalt</dt>
            <dd>{date(stay.checkIn)} – {date(stay.checkOut)}</dd>
            <dt>Belegung</dt>
            <dd>{stay.guests} {stay.guests === 1 ? 'Gast' : 'Gäste'}, {stay.rooms} Zimmer</dd>
          </dl>
          <HolderForm candidate={{ name: stay.hotelName, price: stay.price, currency: stay.currency }} holder={holder} onChange={setHolder} />
        </>
      )}
      {step.kind === 'reserving' && (
        <div className="va-empty va-column va-gap"><Spin /><span className="va-muted">Preis bei {stay?.hotelName} wird gesichert …</span></div>
      )}
      {step.kind === 'pay' && <PaySummary reservation={step.reservation} holder={holder} />}
      {step.kind === 'booking' && <BookingProgress reservation={step.reservation} />}
      {step.kind === 'returned' && (
        <div className="va-empty va-column va-gap"><Spin /><span className="va-muted">Zahlung wird geprüft und gebucht …</span></div>
      )}
      {step.kind === 'booked' && (
        <div className="va-form">
          <div className="va-success">
            <CheckCircleOutlined aria-hidden="true" />
            <span className="va-column">
              <span className="va-strong">{step.reservation.hotelName}</span>
              <span className="va-small">{date(step.reservation.checkIn)} – {date(step.reservation.checkOut)} · {money(step.reservation.price, step.reservation.currency)} mit PayPal</span>
            </span>
          </div>
          {step.reservation.booking?.latitude != null && step.reservation.booking.longitude != null && (
            <a className="va-button va-button-accent va-block" target="_blank" rel="noopener noreferrer"
              href={directionsUrl(step.reservation.booking.latitude, step.reservation.booking.longitude)}>
              <EnvironmentOutlined aria-hidden="true" /> Route zur Unterkunft
            </a>
          )}
          {step.reservation.replacesBookingId != null && (
            <p className="va-small va-muted">Die bisherige Unterkunft steht auf deiner To-do-Liste, damit du sie stornieren kannst.</p>
          )}
        </div>
      )}
      {step.kind === 'attention' && (
        <p className="va-muted">
          {step.reservation.hotelName} ist gebucht. Die Zahlung bei PayPal ist noch nicht abgeschlossen, wir kümmern uns
          darum. Du musst nichts tun.
        </p>
      )}
      {step.kind === 'gone' && (
        <p className="va-muted">
          Kein Angebot ausgewählt. Du kannst eine Unterkunft am Stop auch selbst eintragen.
        </p>
      )}
    </Sheet>
  );

  function footer() {
    switch (step.kind) {
      case 'holder':
        return (
          <div className="va-column va-gap">
            <button type="button" className="va-button va-block" disabled={!holderComplete(holder) || !status?.bookingAvailable}
              onClick={() => void reserve()}>
              Weiter zur Zahlung
            </button>
            <button type="button" className="va-text-button" onClick={() => navigate(`/reisen/${trip.id}/stops/${stop!.id}/buchung`)}>
              Ich buche selbst
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
          </div>
        );
      case 'booked':
        return (
          <button type="button" className="va-button va-block"
            onClick={() => navigate(`/reisen/${trip.id}/buchungen/${step.reservation.bookingId}`)}>
            Buchung ansehen
          </button>
        );
      case 'attention':
      case 'gone':
        return <button type="button" className="va-button va-block" onClick={close}>Zur Reise</button>;
      default:
        return undefined;
    }
  }
}
