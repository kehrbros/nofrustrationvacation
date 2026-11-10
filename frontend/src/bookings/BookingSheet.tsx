import { CarOutlined, CopyOutlined, DeleteOutlined, EditOutlined, ExportOutlined, HomeOutlined, SwapOutlined } from '@ant-design/icons';
import { Alert, Popconfirm } from 'antd';
import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';

import Sheet from '../components/Sheet';
import { formatMoney, t } from '../i18n';
import { useTripContext } from '../trips/tripContext';
import StayConflictDialog from '../trips/StayConflictDialog';
import { bookingOutsideStop, stopDaysLabel, type BookingOutsideStop } from '../trips/stayConflict';
import AddBookingSheet, { SubmitCancelled } from './AddBookingSheet';
import { cancelWithRefund, fetchAlternativesStatus } from './alternativesApi';
import { changeAccommodationBooking, deleteBooking } from './bookingsApi';
import { formFromBooking, toInput } from './form';
import { stopPoint } from './stayDefaults';
import { formatLocalDateTime, formatStay, shortTime } from './localTime';
import { checkInLabel, checkOutLabel, directionsUrl, priceLabel, stayLabel } from './text';

type StopConflictChoice = 'EXTEND_STOP' | 'TRIM_BOOKING';

/** Booking as overlay dialog: stay, number to copy, link to the provider, route, edit and delete. */
export default function BookingSheet() {
  const { bookingId } = useParams();
  const { bookings, stopById, reload, close } = useTripContext();
  const booking = bookings.find((candidate) => candidate.id === Number(bookingId));
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [alternativesAvailable, setAlternativesAvailable] = useState(false);
  const [refund, setRefund] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [conflict, setConflict] = useState<{
    outside: BookingOutsideStop;
    /** Check-in and check-out as entered, local at the place. */
    stay: { startsAt: string; endsAt: string };
    resolve: (choice: StopConflictChoice | null) => void;
  } | null>(null);

  useEffect(() => {
    fetchAlternativesStatus().then((status) => setAlternativesAvailable(status.searchAvailable));
  }, []);

  if (!booking) {
    return (
      <Sheet open onClose={close} title={t('booking.notFound')}>
        <p className="va-muted">{t('booking.gone')}</p>
      </Sheet>
    );
  }
  const accommodation = booking.accommodation;
  const stop = stopById.get(booking.stopId);

  if (editing) {
    return (
      <>
        <AddBookingSheet
          onClose={() => setEditing(false)}
          context={stop ? t('booking.stop', { name: stop.name }) : undefined}
          initialForm={formFromBooking(booking)}
          near={stopPoint(stop)}
          submitLabel={t('booking.saveChanges')}
          onSubmit={async (form) => {
            const input = toInput(form, booking.source);
            try {
              await changeAccommodationBooking(booking.id, input);
            } catch (failure) {
              const outside = bookingOutsideStop(failure);
              if (!outside) {
                throw failure;
              }
              // outside the days of the stop: the user extends the stop, shortens the booking or cancels
              const choice = await new Promise<StopConflictChoice | null>((resolve) =>
                setConflict({ outside, stay: { startsAt: input.checkIn, endsAt: input.checkOut }, resolve }),
              );
              setConflict(null);
              if (!choice) {
                throw new SubmitCancelled();
              }
              await changeAccommodationBooking(booking.id, input, choice);
            }
            reload();
            setEditing(false);
          }}
        />
        {conflict && (
          <StayConflictDialog
            title={t('stayConflict.bookingTitle')}
            text={t('stayConflict.bookingText', {
              stay: formatStay(conflict.stay.startsAt, conflict.stay.endsAt),
              name: stop?.name ?? '',
              days: stopDaysLabel(conflict.outside.stopArrivalAt, conflict.outside.stopDepartureAt),
            })}
            extendLabel={t('stayConflict.extendStop')}
            extendDetail={t('stayConflict.extendDetail', {
              days: stopDaysLabel(
                conflict.outside.extendedArrivalAt ?? conflict.outside.stopArrivalAt,
                conflict.outside.extendedDepartureAt ?? conflict.outside.stopDepartureAt,
              ),
            })}
            trimLabel={t('stayConflict.trimBooking')}
            trimDisabled={!conflict.outside.trimmable}
            busy={false}
            error={null}
            onCancel={() => conflict.resolve(null)}
            onExtend={() => conflict.resolve('EXTEND_STOP')}
            onTrim={() => conflict.resolve('TRIM_BOOKING')}
          />
        )}
      </>
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
      setError(t('booking.deleteFailed'));
    }
  };
  // booked in the app and still free to cancel: cancel at the hotel, PayPal refunds
  const refundable = booking.source === 'SEARCH' && booking.status !== 'CANCELLED' && booking.cancellationDeadline != null
    && new Date(booking.cancellationDeadline).getTime() > Date.now();
  const cancelAndRefund = async () => {
    setCancelling(true);
    setError(null);
    try {
      const reservation = await cancelWithRefund(booking.id);
      setRefund(reservation.refunded
        ? t('booking.refunded', { amount: formatMoney(reservation.refundAmount ?? reservation.price, reservation.currency) })
        : t('booking.refundPending'));
      reload();
    } catch {
      setError(t('booking.cancelFailed'));
    } finally {
      setCancelling(false);
    }
  };
  const houseRules = [
    accommodation?.checkInFrom && t('booking.checkInFrom', { time: shortTime(accommodation.checkInFrom) }),
    accommodation?.checkInUntil && t('booking.checkInUntil', { time: shortTime(accommodation.checkInUntil) }),
    accommodation?.checkOutUntil && t('booking.checkOutUntil', { time: shortTime(accommodation.checkOutUntil) }),
  ].filter(Boolean).join(' · ');

  return (
    <Sheet
      open
      onClose={close}
      icon={<span className="va-booking-icon"><HomeOutlined /></span>}
      eyebrow={[t('addBooking.accommodation'), booking.provider].filter(Boolean).join(' · ')}
      title={booking.title}
      footer={
        <div className="va-row va-gap">
          <button type="button" className="va-button va-button-outline va-grow" onClick={() => setEditing(true)}>
            <EditOutlined aria-hidden="true" /> {t('booking.edit')}
          </button>
          <Popconfirm
            title={t('booking.deleteTitle')}
            description={t('booking.deleteText')}
            okText={t('common.delete')}
            cancelText={t('common.cancel')}
            onConfirm={() => void remove()}
          >
            <button type="button" className="va-button va-button-outline" aria-label={t('booking.delete')}>
              <DeleteOutlined aria-hidden="true" />
            </button>
          </Popconfirm>
        </div>
      }
    >
      {refund && <Alert type="success" showIcon message={refund} />}
      <div className="va-impact">
        <span className="va-eyebrow">{stop ? t('booking.stop', { name: stop.name }) : t('booking.stay')}</span>
        <span className="va-strong">{stayLabel(booking)}</span>
      </div>
      <dl className="va-facts">
        {checkInLabel(booking) && (
          <>
            <dt>{t('booking.checkIn')}</dt>
            <dd>{checkInLabel(booking)}</dd>
          </>
        )}
        {checkOutLabel(booking) && (
          <>
            <dt>{t('booking.checkOut')}</dt>
            <dd>{checkOutLabel(booking)}</dd>
          </>
        )}
        {houseRules && (
          <>
            <dt>{t('booking.houseRules')}</dt>
            <dd>{houseRules}</dd>
          </>
        )}
        {booking.reference && (
          <>
            <dt>{t('booking.reference')}</dt>
            <dd>
              <button type="button" className="va-text-button va-mono" onClick={copy} aria-label={t('booking.copyReference', { reference: booking.reference })}>
                {booking.reference} <CopyOutlined aria-hidden="true" />
              </button>
              {copied && <span className="va-small" role="status"> {t('booking.copied')}</span>}
            </dd>
          </>
        )}
        {accommodation?.addressLine && (
          <>
            <dt>{t('booking.address')}</dt>
            <dd>{accommodation.addressLine}</dd>
          </>
        )}
        {priceLabel(booking) && (
          <>
            <dt>{t('booking.price')}</dt>
            <dd>{priceLabel(booking)}</dd>
          </>
        )}
        {booking.cancellationDeadline && (
          <>
            <dt>{t('booking.cancellation')}</dt>
            <dd>{t('booking.freeUntil', { time: formatLocalDateTime(booking.cancellationDeadline) })}</dd>
          </>
        )}
        {(accommodation?.guests || accommodation?.rooms) && (
          <>
            <dt>{t('booking.occupancy')}</dt>
            <dd>
              {[accommodation.guests && t('common.guests', { count: accommodation.guests }),
                accommodation.rooms && t('common.rooms', { count: accommodation.rooms })]
                .filter(Boolean).join(', ')}
            </dd>
          </>
        )}
        {accommodation?.phone && (
          <>
            <dt>{t('booking.phone')}</dt>
            <dd><a href={`tel:${accommodation.phone.replace(/\s/g, '')}`}>{accommodation.phone}</a></dd>
          </>
        )}
        {accommodation?.email && (
          <>
            <dt>{t('booking.email')}</dt>
            <dd><a href={`mailto:${accommodation.email}`}>{accommodation.email}</a></dd>
          </>
        )}
        {booking.notes && (
          <>
            <dt>{t('booking.notes')}</dt>
            <dd className="va-pre">{booking.notes}</dd>
          </>
        )}
      </dl>
      <div className="va-actions">
        {accommodation && alternativesAvailable && booking.status !== 'CANCELLED' && (
          <Link className="va-button va-button-accent va-block" to="alternativen">
            <SwapOutlined aria-hidden="true" /> {t('booking.findAlternative')}
          </Link>
        )}
        {refundable && (
          <Popconfirm
            title={t('booking.cancelTitle')}
            description={t('booking.cancelText')}
            okText={t('booking.cancelOk')}
            cancelText={t('booking.keep')}
            onConfirm={() => void cancelAndRefund()}
          >
            <button type="button" className="va-button va-button-outline va-block" disabled={cancelling}>
              {cancelling ? t('booking.cancelling') : t('booking.cancelButton')}
            </button>
          </Popconfirm>
        )}
        {booking.manageUrl && (
          <a className="va-button va-button-outline va-block" href={booking.manageUrl} target="_blank" rel="noopener noreferrer">
            <ExportOutlined aria-hidden="true" /> {booking.provider ? t('booking.openAt', { provider: booking.provider }) : t('booking.open')}
          </a>
        )}
        {accommodation?.latitude != null && accommodation.longitude != null && (
          <a
            className="va-button va-button-outline va-block"
            href={directionsUrl(accommodation.latitude, accommodation.longitude)}
            target="_blank"
            rel="noopener noreferrer"
          >
            <CarOutlined aria-hidden="true" /> {t('booking.directions')}
          </a>
        )}
        {accommodation?.website && (
          <a className="va-text-button" href={accommodation.website} target="_blank" rel="noopener noreferrer">
            {t('booking.website')}
          </a>
        )}
      </div>
      {accommodation && accommodation.latitude == null && (
        <p className="va-small va-muted">{t('booking.noPosition', { zone: accommodation.timeZone })}</p>
      )}
      {error && <Alert type="error" showIcon message={error} />}
    </Sheet>
  );
}
