import { CheckCircleOutlined, EnvironmentOutlined } from '@ant-design/icons';
import { Alert, Spin } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router';

import Sheet from '../components/Sheet';
import { t } from '../i18n';
import PayPalButton from '../payments/PayPalButton';
import type { ProposalStay } from '../proposals/proposalsApi';
import { useTripContext } from '../trips/tripContext';
import {
  BookingProgress,
  date,
  errorText,
  failureText,
  HolderForm,
  money,
  PaySummary,
} from './AlternativesSheet';
import {
  abortReservation,
  bookReservation,
  fetchAlternativesStatus,
  fetchReservation,
  reserveStay,
  type AlternativesStatus,
  type Reservation,
} from './alternativesApi';
import { holderComplete, useHolder } from './holder';
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
  const guest = useHolder(trip, reload);
  const holder = guest.holder;
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
      guest.saved();
      setStep({ kind: 'pay', reservation });
    } catch (failure) {
      guest.refused(failure);
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
        () => setError(t('payment.cancelledAtPayPal')),
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
      <Sheet open onClose={close} title={t('stay.stopNotFound')}>
        <p className="va-muted">{t('stay.stopGone')}</p>
      </Sheet>
    );
  }

  const title = step.kind === 'booked' ? t('payment.booked') : step.kind === 'attention' ? t('payment.bookedChecking')
    : stay?.hotelName ?? t('stay.book');
  return (
    <Sheet open onClose={close} eyebrow={t('stay.eyebrow', { name: stop.name })} title={title} height="90vh" footer={footer()}>
      {status && !status.bookingAvailable && step.kind === 'holder' && (
        <Alert type="info" showIcon message={t('stay.unavailable')} />
      )}
      {error && <Alert type="error" showIcon message={error} closable onClose={() => setError(null)} />}
      {notice && <Alert type="info" showIcon message={notice} closable onClose={() => setNotice(null)} />}
      {step.kind === 'holder' && stay && (
        <>
          <dl className="va-facts">
            <dt>{t('booking.stay')}</dt>
            <dd>{date(stay.checkIn)} – {date(stay.checkOut)}</dd>
            <dt>{t('booking.occupancy')}</dt>
            <dd>{t('common.guests', { count: stay.guests })}, {t('common.rooms', { count: stay.rooms })}</dd>
          </dl>
          <HolderForm candidate={{ name: stay.hotelName, price: stay.price, currency: stay.currency }} state={guest} />
        </>
      )}
      {step.kind === 'reserving' && (
        <div className="va-empty va-column va-gap"><Spin /><span className="va-muted">{t('payment.securingPrice', { hotel: stay?.hotelName ?? '' })}</span></div>
      )}
      {step.kind === 'pay' && <PaySummary reservation={step.reservation} holder={holder} />}
      {step.kind === 'booking' && <BookingProgress reservation={step.reservation} />}
      {step.kind === 'returned' && (
        <div className="va-empty va-column va-gap"><Spin /><span className="va-muted">{t('payment.checkingAndBooking')}</span></div>
      )}
      {step.kind === 'booked' && (
        <div className="va-form">
          <div className="va-success">
            <CheckCircleOutlined aria-hidden="true" />
            <span className="va-column">
              <span className="va-strong">{step.reservation.hotelName}</span>
              <span className="va-small">{date(step.reservation.checkIn)} – {date(step.reservation.checkOut)} · {t('payment.withPayPal', { amount: money(step.reservation.price, step.reservation.currency) })}</span>
            </span>
          </div>
          {step.reservation.booking?.latitude != null && step.reservation.booking.longitude != null && (
            <a className="va-button va-button-accent va-block" target="_blank" rel="noopener noreferrer"
              href={directionsUrl(step.reservation.booking.latitude, step.reservation.booking.longitude)}>
              <EnvironmentOutlined aria-hidden="true" /> {t('payment.directions')}
            </a>
          )}
          {step.reservation.replacesBookingId != null && (
            <p className="va-small va-muted">{t('payment.previousOnTodo')}</p>
          )}
        </div>
      )}
      {step.kind === 'attention' && (
        <p className="va-muted">
          {t('payment.attention', { hotel: step.reservation.hotelName })}
        </p>
      )}
      {step.kind === 'gone' && (
        <p className="va-muted">
          {t('stay.noOffer')}
        </p>
      )}
    </Sheet>
  );

  function footer() {
    switch (step.kind) {
      case 'holder':
        return (
          <div className="va-column va-gap">
            <button type="button" className="va-button va-block" disabled={!status?.bookingAvailable}
              onClick={() => guest.check() && void reserve()}>
              {t('payment.continue')}
            </button>
            <button type="button" className="va-text-button" onClick={() => navigate(`/reisen/${trip.id}/stops/${stop!.id}/buchung`)}>
              {t('stay.bookMyself')}
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
          </div>
        );
      case 'booked':
        return (
          <button type="button" className="va-button va-block"
            onClick={() => navigate(`/reisen/${trip.id}/buchungen/${step.reservation.bookingId}`)}>
            {t('payment.viewBooking')}
          </button>
        );
      case 'attention':
      case 'gone':
        return <button type="button" className="va-button va-block" onClick={close}>{t('payment.toTrip')}</button>;
      default:
        return undefined;
    }
  }
}
