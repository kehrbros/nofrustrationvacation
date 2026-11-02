import { CarOutlined, CopyOutlined, DeleteOutlined, EditOutlined, ExportOutlined, HomeOutlined } from '@ant-design/icons';
import { Alert, Popconfirm } from 'antd';
import { useState } from 'react';
import { useParams } from 'react-router';

import Sheet from '../components/Sheet';
import { useTripContext } from '../trips/tripContext';
import AddBookingSheet from './AddBookingSheet';
import { changeAccommodationBooking, deleteBooking } from './bookingsApi';
import { formFromBooking, toInput } from './form';
import { formatLocalDateTime, shortTime } from './localTime';
import { checkInLabel, checkOutLabel, directionsUrl, priceLabel, stayLabel } from './text';

/** Booking as overlay dialog: stay, number to copy, link to the provider, route, edit and delete. */
export default function BookingSheet() {
  const { bookingId } = useParams();
  const { bookings, stopById, reload, close } = useTripContext();
  const booking = bookings.find((candidate) => candidate.id === Number(bookingId));
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  if (!booking) {
    return (
      <Sheet open onClose={close} title="Buchung nicht gefunden">
        <p className="va-muted">Diese Buchung gibt es nicht mehr.</p>
      </Sheet>
    );
  }
  const accommodation = booking.accommodation;
  const stop = stopById.get(booking.stopId);

  if (editing) {
    return (
      <AddBookingSheet
        onClose={() => setEditing(false)}
        context={stop ? `Stop ${stop.name}` : undefined}
        initialForm={formFromBooking(booking)}
        submitLabel="Änderungen speichern"
        onSubmit={async (form) => {
          await changeAccommodationBooking(booking.id, toInput(form, booking.source));
          reload();
          setEditing(false);
        }}
      />
    );
  }

  const copy = () => {
    if (booking.reference) {
      navigator.clipboard?.writeText(booking.reference).then(
        () => setCopied(true),
        () => undefined,
      );
    }
  };
  const remove = async () => {
    try {
      await deleteBooking(booking.id);
      reload();
      close();
    } catch {
      setError('Die Buchung konnte nicht gelöscht werden.');
    }
  };
  const houseRules = [
    accommodation?.checkInFrom && `Check-in ab ${shortTime(accommodation.checkInFrom)}`,
    accommodation?.checkInUntil && `bis ${shortTime(accommodation.checkInUntil)}`,
    accommodation?.checkOutUntil && `Check-out bis ${shortTime(accommodation.checkOutUntil)}`,
  ].filter(Boolean).join(' · ');

  return (
    <Sheet
      open
      onClose={close}
      icon={<span className="va-booking-icon"><HomeOutlined /></span>}
      eyebrow={['Unterkunft', booking.provider].filter(Boolean).join(' · ')}
      title={booking.title}
      footer={
        <div className="va-row va-gap">
          <button type="button" className="va-button va-button-outline va-grow" onClick={() => setEditing(true)}>
            <EditOutlined aria-hidden="true" /> Bearbeiten
          </button>
          <Popconfirm
            title="Buchung löschen?"
            description="Die Buchung wird aus der Reise entfernt."
            okText="Löschen"
            cancelText="Abbrechen"
            onConfirm={() => void remove()}
          >
            <button type="button" className="va-button va-button-outline" aria-label="Buchung löschen">
              <DeleteOutlined aria-hidden="true" />
            </button>
          </Popconfirm>
        </div>
      }
    >
      <div className="va-impact">
        <span className="va-eyebrow">{stop ? `Stop ${stop.name}` : 'Aufenthalt'}</span>
        <span className="va-strong">{stayLabel(booking)}</span>
      </div>
      <dl className="va-facts">
        {checkInLabel(booking) && (
          <>
            <dt>Check-in</dt>
            <dd>{checkInLabel(booking)}</dd>
          </>
        )}
        {checkOutLabel(booking) && (
          <>
            <dt>Check-out</dt>
            <dd>{checkOutLabel(booking)}</dd>
          </>
        )}
        {houseRules && (
          <>
            <dt>Hausregeln</dt>
            <dd>{houseRules}</dd>
          </>
        )}
        {booking.reference && (
          <>
            <dt>Buchungsnr.</dt>
            <dd>
              <button type="button" className="va-text-button va-mono" onClick={copy} aria-label={`Buchungsnummer ${booking.reference} kopieren`}>
                {booking.reference} <CopyOutlined aria-hidden="true" />
              </button>
              {copied && <span className="va-small" role="status"> kopiert</span>}
            </dd>
          </>
        )}
        {accommodation?.addressLine && (
          <>
            <dt>Adresse</dt>
            <dd>{accommodation.addressLine}</dd>
          </>
        )}
        {priceLabel(booking) && (
          <>
            <dt>Preis</dt>
            <dd>{priceLabel(booking)}</dd>
          </>
        )}
        {booking.cancellationDeadline && (
          <>
            <dt>Storno</dt>
            <dd>kostenlos bis {formatLocalDateTime(booking.cancellationDeadline)}</dd>
          </>
        )}
        {(accommodation?.guests || accommodation?.rooms) && (
          <>
            <dt>Belegung</dt>
            <dd>
              {[accommodation.guests && `${accommodation.guests} ${accommodation.guests === 1 ? 'Gast' : 'Gäste'}`,
                accommodation.rooms && `${accommodation.rooms} ${accommodation.rooms === 1 ? 'Zimmer' : 'Zimmer'}`]
                .filter(Boolean).join(', ')}
            </dd>
          </>
        )}
        {accommodation?.phone && (
          <>
            <dt>Telefon</dt>
            <dd><a href={`tel:${accommodation.phone.replace(/\s/g, '')}`}>{accommodation.phone}</a></dd>
          </>
        )}
        {accommodation?.email && (
          <>
            <dt>E-Mail</dt>
            <dd><a href={`mailto:${accommodation.email}`}>{accommodation.email}</a></dd>
          </>
        )}
        {booking.notes && (
          <>
            <dt>Notiz</dt>
            <dd className="va-pre">{booking.notes}</dd>
          </>
        )}
      </dl>
      <div className="va-actions">
        {booking.manageUrl && (
          <a className="va-button va-button-outline va-block" href={booking.manageUrl} target="_blank" rel="noopener noreferrer">
            <ExportOutlined aria-hidden="true" /> {booking.provider ? `Bei ${booking.provider} öffnen` : 'Buchung öffnen'}
          </a>
        )}
        {accommodation?.latitude != null && accommodation.longitude != null && (
          <a
            className="va-button va-button-outline va-block"
            href={directionsUrl(accommodation.latitude, accommodation.longitude)}
            target="_blank"
            rel="noopener noreferrer"
          >
            <CarOutlined aria-hidden="true" /> Route dorthin
          </a>
        )}
        {accommodation?.website && (
          <a className="va-text-button" href={accommodation.website} target="_blank" rel="noopener noreferrer">
            Website der Unterkunft
          </a>
        )}
      </div>
      {accommodation && accommodation.latitude == null && (
        <p className="va-small va-muted">Ohne Kartenposition, Zeiten in der Zeitzone {accommodation.timeZone}.</p>
      )}
      {error && <Alert type="error" showIcon message={error} />}
    </Sheet>
  );
}
