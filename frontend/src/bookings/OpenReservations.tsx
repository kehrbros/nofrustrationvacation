import { LoadingOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';

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
    ? `Zahlung für ${reservation.hotelName} offen`
    : attention
      ? `${reservation.hotelName} ist gebucht, die Zahlung wird geprüft`
      : `Umbuchung zu ${reservation.hotelName} läuft`;
  const content = (
    <>
      {attention ? <SafetyCertificateOutlined aria-hidden="true" /> : pending ? <SafetyCertificateOutlined aria-hidden="true" /> : <LoadingOutlined aria-hidden="true" />}
      <span className="va-column">
        <span className="va-strong">{text}</span>
        <span className="va-small">
          {pending ? 'Bei PayPal ist noch nichts abgebucht. Tippen zum Fortsetzen.'
            : attention ? 'Du musst nichts tun, der Betrag ist bei PayPal nur reserviert.'
              : 'Abgebucht wird erst, wenn das Hotel bestätigt hat.'}
        </span>
      </span>
    </>
  );
  return replaced && !attention ? (
    <Link className="va-trip-banner" to={`buchungen/${replaced.id}/alternativen?reservierung=${reservation.id}`} role="status">
      {content}
    </Link>
  ) : (
    <div className="va-trip-banner" role="status">{content}</div>
  );
}
