import { SafetyCertificateOutlined } from '@ant-design/icons';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';

import KiwiLoader from '../components/KiwiLoader';
import { t } from '../i18n';
import type { Booking } from './model';
import { fetchUnfinishedReservations, type Reservation } from './alternativesApi';

const POLL_MS = 15_000;

/**
 * Banner over the map while a rebooking is not finished: the payment was approved at PayPal and the booking still
 * runs (also in the background, if the app was closed), or it has to be checked. Nobody should wonder where the money
 * went. Tapping continues in the sheet of the alternatives.
 */
export default function OpenReservations({ tripId, bookings, onFinished }: {
  tripId: number;
  bookings: Booking[];
  onFinished: () => void;
}) {
  const [open, setOpen] = useState<Reservation[]>([]);
  const finished = useRef(onFinished);
  finished.current = onFinished;

  useEffect(() => {
    let active = true;
    let previous = 0;
    const load = () => fetchUnfinishedReservations(tripId).then((reservations) => {
      if (!active) return;
      if (reservations.length < previous) {
        // finished in the background: show the new booking
        finished.current();
      }
      previous = reservations.length;
      setOpen(reservations);
    }, () => undefined);
    void load();
    const timer = window.setInterval(() => void load(), POLL_MS);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [tripId]);

  if (open.length === 0) {
    return null;
  }
  const reservation = open[0];
  const replaced = bookings.find((booking) => booking.id === reservation.replacesBookingId);
  const pending = reservation.status === 'PAYMENT_PENDING';
  const attention = reservation.status === 'NEEDS_ATTENTION';
  const text = pending
    ? t('reservation.pending', { hotel: reservation.hotelName })
    : attention
      ? t('reservation.attention', { hotel: reservation.hotelName })
      : t('reservation.running', { hotel: reservation.hotelName });
  const content = (
    <>
      {attention ? <SafetyCertificateOutlined aria-hidden="true" /> : pending ? <SafetyCertificateOutlined aria-hidden="true" /> : <KiwiLoader size="small" decorative />}
      <span className="va-column">
        <span className="va-strong">{text}</span>
        <span className="va-small">
          {pending ? t('reservation.pendingHint')
            : attention ? t('reservation.attentionHint')
              : t('reservation.runningHint')}
        </span>
      </span>
    </>
  );
  if (!attention && (replaced || reservation.replacesBookingId == null)) {
    // continues in the sheet the payment started in: alternative to a booking, or a stay at the stop
    const to = replaced ? `buchungen/${replaced.id}/alternativen?reservierung=${reservation.id}`
      : `stops/${reservation.stopId}/unterkunft?reservierung=${reservation.id}`;
    return <Link className="va-trip-banner" to={to} role="status">{content}</Link>;
  }
  return (

    <div className="va-trip-banner" role="status">{content}</div>
  );
}
