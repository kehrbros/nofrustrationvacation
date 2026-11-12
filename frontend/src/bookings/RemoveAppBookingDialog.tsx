import { Alert, Modal } from 'antd';
import { useState } from 'react';

import { formatMoney, t } from '../i18n';
import { cancelWithRefund } from './alternativesApi';
import { isFreeToCancel } from './appBookings';
import { formatLocalDateTime } from './localTime';
import type { Booking } from './model';

interface Props {
  /** Bookings made in the app that are removed with it (see isAppBooked). */
  bookings: Booking[];
  /** Removing a single booking, or deleting its stop (stopName). */
  stopName?: string;
  /** Further effects of the removal, e.g. routes planned again. */
  detail?: string;
  /** Removes the booking or deletes the stop; may throw. */
  remove: () => Promise<void>;
  onClose: () => void;
}

/**
 * Removing a booking made and paid in the app (or its stop): cancel it as well (at the hotel, PayPal refunds) or
 * only remove it, then it goes on the to-do list and can still be cancelled there. See Docs/domain.md#bookings.
 */
export default function RemoveAppBookingDialog({ bookings, stopName, detail, remove, onClose }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const forStop = stopName !== undefined;
  const free = bookings.filter((booking) => isFreeToCancel(booking));

  const run = async (cancel: boolean) => {
    setBusy(true);
    setError(null);
    if (cancel) {
      try {
        for (const booking of free) {
          await cancelWithRefund(booking.id);
        }
      } catch {
        setError(t('removeBooking.cancelFailed'));
        setBusy(false);
        return;
      }
    }
    try {
      await remove();
      onClose();
    } catch {
      // the caller shows its own message for the removal; after a cancellation say what happened
      if (cancel) {
        setError(t('removeBooking.removeFailed'));
      } else {
        onClose();
      }
    } finally {
      setBusy(false);
    }
  };

  const title = forStop
    ? t('removeBooking.titleStop', { name: stopName })
    : t('removeBooking.titleBooking', { title: bookings[0]?.title ?? '' });
  return (
    <Modal open title={title} onCancel={busy ? undefined : onClose} footer={null} centered>
      <div className="va-form">
        <p>{t('removeBooking.intro', { count: bookings.length, title: bookings[0]?.title ?? '' })}</p>
        <ul className="va-conflict-list">
          {bookings.map((booking) => (
            <li key={booking.id}>
              {bookings.length > 1 && <span className="va-strong">{booking.title}: </span>}
              {cancellationText(booking)}
            </li>
          ))}
        </ul>
        {detail && <p className="va-small va-muted">{detail}</p>}
        <div className="va-actions">
          <button type="button" className="va-button va-button-accent va-block" disabled={busy || free.length === 0}
            onClick={() => void run(true)}>
            {busy ? t('removeBooking.working') : forStop ? t('removeBooking.cancelAndDeleteStop') : t('removeBooking.cancelAndRemove')}
          </button>
          <span className="va-small va-muted">
            {free.length === 0 ? t('removeBooking.nothingFree') : t('removeBooking.cancelHint')}
          </span>
          <button type="button" className="va-button va-button-outline va-block" disabled={busy} onClick={() => void run(false)}>
            {forStop ? t('removeBooking.deleteStopOnly') : t('removeBooking.removeOnly')}
          </button>
          <span className="va-small va-muted">{t('removeBooking.removeOnlyHint')}</span>
          <button type="button" className="va-text-button" disabled={busy} onClick={onClose}>
            {t('common.cancel')}
          </button>
        </div>
        {error && <Alert type="error" showIcon message={error} />}
      </div>
    </Modal>
  );
}

function cancellationText(booking: Booking): string {
  if (!isFreeToCancel(booking) || !booking.cancellationDeadline) {
    return t('removeBooking.notFree');
  }
  const time = formatLocalDateTime(booking.cancellationDeadline);
  return booking.price && booking.currency
    ? t('removeBooking.free', { time, amount: formatMoney(booking.price, booking.currency) })
    : t('removeBooking.freeNoPrice', { time });
}
