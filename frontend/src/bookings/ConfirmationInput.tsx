import { FileTextOutlined, LinkOutlined, PaperClipOutlined, SnippetsOutlined } from '@ant-design/icons';
import { Alert } from 'antd';

import { t } from '../i18n';
import type { SharedContent } from './shareTarget';

interface Props {
  /** Shared to the app and not yet saved, read together with the pasted text and the chosen file. */
  shared: SharedContent | null;
  onSharedChange: (shared: SharedContent | null) => void;
  text: string;
  onTextChange: (text: string) => void;
  file: File | null;
  onFileChange: (file: File | null) => void;
  /** False if the server has no assistant. */
  available: boolean | null;
  error: string | null;
  onError: (error: string | null) => void;
  onManual: () => void;
}

const MAX_FILE_BYTES = 5 * 1024 * 1024;

/**
 * "Bestätigung einfügen": text, screenshot or PDF of a confirmation, combined with what was shared to the app.
 * The caller reads it with the assistant (combineInput, extractAccommodation).
 */
export default function ConfirmationInput({
  shared,
  onSharedChange,
  text,
  onTextChange,
  file,
  onFileChange,
  available,
  error,
  onError,
  onManual,
}: Props) {
  const sharedText = shared?.text.trim() ?? '';
  const sharedFile = file ? null : (shared?.file ?? null);
  const shownFile = file ?? sharedFile;

  const removeSharedText = () => onSharedChange(shared?.file ? { text: '', file: shared.file } : null);
  const removeFile = () => {
    if (file) {
      onFileChange(null);
    } else {
      onSharedChange(sharedText ? { text: sharedText, file: null } : null);
    }
  };

  return (
    <div className="va-form">
      {available === false && (
        <Alert
          type="info"
          showIcon
          message={t('confirmation.unavailable')}
          description={t('confirmation.unavailableHint')}
        />
      )}
      {sharedText && (
        <div className="va-field">
          <span className="va-label">{t('confirmation.shared')}</span>
          <span className="va-file">
            {/^https?:\/\/\S+$/.test(sharedText) ? <LinkOutlined aria-hidden="true" /> : <SnippetsOutlined aria-hidden="true" />}
            <span className="va-grow va-ellipsis">{sharedText}</span>
            <button type="button" className="va-text-button" onClick={removeSharedText}>{t('confirmation.remove')}</button>
          </span>
          <span className="va-small va-muted">{t('confirmation.sharedHint')}</span>
        </div>
      )}
      <div className="va-field">
        <label htmlFor="booking-text">{sharedText ? t('confirmation.moreText') : t('confirmation.text')}</label>
        <textarea
          id="booking-text"
          rows={7}
          placeholder={t('confirmation.placeholder')}
          value={text}
          onChange={(event) => onTextChange(event.target.value)}
        />
        {typeof navigator !== 'undefined' && navigator.clipboard?.readText && (
          <button
            type="button"
            className="va-text-button"
            onClick={() => {
              navigator.clipboard.readText().then((pasted) => pasted && onTextChange(pasted), () => undefined);
            }}
          >
            <SnippetsOutlined aria-hidden="true" /> {t('confirmation.pasteClipboard')}
          </button>
        )}
      </div>
      <div className="va-divider" role="separator"><span>{t('confirmation.andOr')}</span></div>
      <div className="va-field">
        <span className="va-label">{t('confirmation.file')}</span>
        {shownFile ? (
          <span className="va-file">
            {shownFile.type === 'application/pdf' ? <FileTextOutlined aria-hidden="true" /> : <PaperClipOutlined aria-hidden="true" />}
            <span className="va-grow va-ellipsis">{shownFile.name}{sharedFile && ` ${t('confirmation.sharedFile')}`}</span>
            <button type="button" className="va-text-button" onClick={removeFile}>{t('confirmation.remove')}</button>
          </span>
        ) : (
          <label className="va-button va-button-outline va-block va-file-pick">
            <PaperClipOutlined aria-hidden="true" /> {t('confirmation.chooseFile')}
            <input
              type="file"
              accept="image/jpeg,image/png,image/webp,image/gif,application/pdf"
              className="va-visually-hidden"
              onChange={(event) => {
                const chosen = event.target.files?.[0] ?? null;
                if (chosen && chosen.size > MAX_FILE_BYTES) {
                  onError(t('confirmation.fileTooLarge'));
                  return;
                }
                onError(null);
                onFileChange(chosen);
              }}
            />
          </label>
        )}
      </div>
      <p className="va-small va-muted">
        {t('confirmation.privacy')}
      </p>
      {error && (
        <Alert
          type="warning"
          showIcon
          message={error}
          action={<button type="button" className="va-text-button" onClick={onManual}>{t('addBooking.manual')}</button>}
        />
      )}
    </div>
  );
}
