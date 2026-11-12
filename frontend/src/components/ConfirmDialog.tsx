import { Modal } from 'antd';

import { t } from '../i18n';

interface Props {
  title: string;
  text: string;
  confirmLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * Confirmation of a destructive step as a centered dialog: unlike a popover it stays readable on small screens,
 * whatever the position of the button that opened it.
 */
export default function ConfirmDialog({ title, text, confirmLabel, onConfirm, onCancel }: Props) {
  return (
    <Modal open title={title} onCancel={onCancel} footer={null} centered>
      <div className="va-form">
        <p>{text}</p>
        <div className="va-actions">
          <button type="button" className="va-button va-button-danger va-block" onClick={onConfirm}>
            {confirmLabel}
          </button>
          <button type="button" className="va-button va-button-outline va-block" onClick={onCancel}>
            {t('common.cancel')}
          </button>
        </div>
      </div>
    </Modal>
  );
}
