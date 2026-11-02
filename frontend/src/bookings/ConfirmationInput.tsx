import { FileTextOutlined, LinkOutlined, PaperClipOutlined, SnippetsOutlined } from '@ant-design/icons';
import { Alert } from 'antd';

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
          message="Automatisches Auslesen ist auf diesem Server nicht eingerichtet."
          description="Du kannst die Buchung manuell eingeben."
        />
      )}
      {sharedText && (
        <div className="va-field">
          <span className="va-label">An die App geteilt</span>
          <span className="va-file">
            {/^https?:\/\/\S+$/.test(sharedText) ? <LinkOutlined aria-hidden="true" /> : <SnippetsOutlined aria-hidden="true" />}
            <span className="va-grow va-ellipsis">{sharedText}</span>
            <button type="button" className="va-text-button" onClick={removeSharedText}>entfernen</button>
          </span>
          <span className="va-small va-muted">Wird zusammen mit dem eingefügten Text gelesen.</span>
        </div>
      )}
      <div className="va-field">
        <label htmlFor="booking-text">{sharedText ? 'Weiterer Text der Bestätigung' : 'Text der Bestätigung'}</label>
        <textarea
          id="booking-text"
          rows={7}
          placeholder="Text der Bestätigungsmail hier einfügen …"
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
            <SnippetsOutlined aria-hidden="true" /> Aus Zwischenablage einfügen
          </button>
        )}
      </div>
      <div className="va-divider" role="separator"><span>und / oder</span></div>
      <div className="va-field">
        <span className="va-label">Screenshot oder PDF</span>
        {shownFile ? (
          <span className="va-file">
            {shownFile.type === 'application/pdf' ? <FileTextOutlined aria-hidden="true" /> : <PaperClipOutlined aria-hidden="true" />}
            <span className="va-grow va-ellipsis">{shownFile.name}{sharedFile && ' (geteilt)'}</span>
            <button type="button" className="va-text-button" onClick={removeFile}>entfernen</button>
          </span>
        ) : (
          <label className="va-button va-button-outline va-block va-file-pick">
            <PaperClipOutlined aria-hidden="true" /> Datei wählen
            <input
              type="file"
              accept="image/jpeg,image/png,image/webp,image/gif,application/pdf"
              className="va-visually-hidden"
              onChange={(event) => {
                const chosen = event.target.files?.[0] ?? null;
                if (chosen && chosen.size > MAX_FILE_BYTES) {
                  onError('Die Datei ist größer als 5 MB.');
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
        Wird zur Auswertung an OpenAI übertragen und nicht gespeichert. PIN, Zahlungs- und Gästedaten übernehmen wir nicht.
      </p>
      {error && (
        <Alert
          type="warning"
          showIcon
          message={error}
          action={<button type="button" className="va-text-button" onClick={onManual}>Manuell eingeben</button>}
        />
      )}
    </div>
  );
}
