import { Alert, Modal } from 'antd';

import { t } from '../i18n';

interface Props {
  title: string;
  /** What does not fit, e.g. which bookings lie outside the stop. */
  text: string;
  items?: string[];
  extendLabel: string;
  /** New days of the stop when extending. */
  extendDetail: string;
  trimLabel: string;
  /** Shortening is not possible (booked in the app, no night left). */
  trimDisabled: boolean;
  busy: boolean;
  error: string | null;
  onCancel: () => void;
  onExtend: () => void;
  onTrim: () => void;
}

/**
 * Bookings and stop do not fit (Docs/domain.md#bookings): cancel, extend the stop to the bookings, or
 * shorten the bookings to the stop. Used when changing a stop and when changing a booking.
 */
export default function StayConflictDialog({ title, text, items, extendLabel, extendDetail, trimLabel, trimDisabled, busy, error, onCancel, onExtend, onTrim }: Props) {
  return (
    <Modal open title={title} onCancel={onCancel} footer={null} centered>
      <div className="va-form">
        <p>{text}</p>
        {items && items.length > 0 && (
          <ul className="va-conflict-list">
            {items.map((item) => <li key={item}>{item}</li>)}
          </ul>
        )}
        <div className="va-actions">
          <button type="button" className="va-button va-button-accent va-block" disabled={busy} onClick={onExtend}>
            {extendLabel}
          </button>
          <span className="va-small va-muted">{extendDetail}</span>
          <button type="button" className="va-button va-button-outline va-block" disabled={busy || trimDisabled} onClick={onTrim}>
            {trimLabel}
          </button>
          <span className="va-small va-muted">{trimDisabled ? t('stayConflict.notTrimmable') : t('stayConflict.trimHint')}</span>
          <button type="button" className="va-text-button" disabled={busy} onClick={onCancel}>
            {t('common.cancel')}
          </button>
        </div>
        {error && <Alert type="error" showIcon message={error} />}
      </div>
    </Modal>
  );
}
