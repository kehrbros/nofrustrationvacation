import { AudioOutlined, EditOutlined, SnippetsOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useEffect, useState } from 'react';

import Sheet from '../components/Sheet';
import AccommodationFields from './AccommodationFields';
import { isExtractionAvailable } from './bookingsApi';
import ConfirmationInput from './ConfirmationInput';
import { emptyForm, validateForm, withStayDefaults, type AccommodationForm, type FormField, type StayDefaults } from './form';
import type { BookingSource } from './model';
import { readConfirmation } from './readConfirmation';
import { clearShared, readShared, type SharedContent } from './shareTarget';

type Step = 'choose' | 'paste' | 'review';

interface Props {
  onClose: () => void;
  /** Saves (or hands over) the checked booking; a rejected promise keeps the sheet open with its message. */
  onSubmit: (form: AccommodationForm, source: BookingSource) => Promise<void>;
  /** Shown below the title, e.g. the stop. */
  context?: string;
  /** Edit an existing booking: starts with the filled form. */
  initialForm?: AccommodationForm;
  submitLabel?: string;
  /** Dates of the stay at the stop and travelers of the trip, fill what is still empty. */
  defaults?: StayDefaults;
  /** The stop: name suggestions near it come first. */
  near?: { latitude: number; longitude: number } | null;
}

/**
 * "Buchung hinzufügen": first accommodations. Ways in: paste a confirmation (text, screenshot, PDF; read by the
 * assistant, together with content shared to the app and not yet saved), manual input. Every way ends in the same
 * form the user checks before saving.
 */
export default function AddBookingSheet({ onClose, onSubmit, context, initialForm, submitLabel, defaults, near }: Props) {
  const [step, setStep] = useState<Step>(initialForm ? 'review' : 'choose');
  const [form, setForm] = useState<AccommodationForm>(initialForm ?? withStayDefaults(emptyForm(), defaults));
  const [filled, setFilled] = useState<Set<FormField>>(new Set());
  const [uncertain, setUncertain] = useState<Set<FormField>>(new Set());
  const [source, setSource] = useState<BookingSource>('MANUAL');
  const [text, setText] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [shared, setShared] = useState<SharedContent | null>(null);
  const [reading, setReading] = useState(false);
  const [readError, setReadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [available, setAvailable] = useState<boolean | null>(null);

  useEffect(() => {
    void isExtractionAvailable().then(setAvailable);
    if (!initialForm) {
      readShared().then(setShared, () => undefined);
    }
  }, [initialForm]);

  const manual = () => {
    setForm(withStayDefaults(emptyForm(), defaults));
    setSource('MANUAL');
    setFilled(new Set());
    setUncertain(new Set());
    setStep('review');
  };

  const read = async () => {
    setReading(true);
    setReadError(null);
    const result = await readConfirmation(shared, text, file);
    setReading(false);
    if (!result.ok) {
      setReadError(result.message);
      return;
    }
    setForm(withStayDefaults(result.filled.form, defaults));
    setFilled(result.filled.filled);
    setUncertain(result.filled.uncertain);
    setSource(shared ? 'SHARE' : 'DOCUMENT');
    setStep('review');
  };

  const submit = async () => {
    const problem = validateForm(form);
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await onSubmit(form, source);
      if (source === 'SHARE') {
        await clearShared().catch(() => undefined);
      }
    } catch {
      setError('Die Buchung konnte nicht gespeichert werden.');
      setSaving(false);
    }
  };

  const titles: Record<Step, string> = {
    choose: 'Buchung hinzufügen',
    paste: 'Bestätigung einfügen',
    review: initialForm ? 'Buchung bearbeiten' : 'Buchung prüfen',
  };
  const back = step === 'choose' || initialForm ? undefined : () => setStep(step === 'review' && source !== 'MANUAL' ? 'paste' : 'choose');

  const combinedEmpty = !text.trim() && !file && !shared?.text.trim() && !shared?.file;

  return (
    <Sheet
      open
      onClose={onClose}
      eyebrow={context}
      title={titles[step]}
      height="92vh"
      footer={footer()}
    >
      {back && (
        <button type="button" className="va-text-button va-back" onClick={back}>
          ← Zurück
        </button>
      )}
      {step === 'choose' && (
        <div className="va-form">
          <div className="va-row va-gap va-wrap" role="group" aria-label="Art der Buchung">
            <span className="va-chip va-chip-ink" aria-current="true">Unterkunft</span>
            <span className="va-chip va-chip-off">Transport · folgt</span>
            <span className="va-chip va-chip-off">Aktivität · folgt</span>
          </div>
          <div className="va-choices">
            <button type="button" className="va-choice va-choice-main" onClick={() => setStep('paste')}>
              <SnippetsOutlined aria-hidden="true" />
              <span className="va-column">
                <span className="va-option-title">Bestätigung einfügen <span className="va-ai-mark">✦</span></span>
                <span className="va-small va-muted">
                  {shared
                    ? 'An die App Geteiltes liegt bereit, bei Bedarf um Mail-Text, Screenshot oder PDF ergänzen.'
                    : 'Mail-Text, Screenshot oder PDF. Wir lesen Unterkunft, Zeitraum und Buchungsnummer aus.'}
                </span>
              </span>
            </button>
            <button type="button" className="va-choice" onClick={manual}>
              <EditOutlined aria-hidden="true" />
              <span className="va-column">
                <span className="va-option-title">Manuell eingeben</span>
                <span className="va-small va-muted">Namen eintippen und Vorschlag wählen, den Rest ergänzen wir.</span>
              </span>
            </button>
            <div className="va-choice va-choice-off" aria-disabled="true">
              <AudioOutlined aria-hidden="true" />
              <span className="va-column">
                <span className="va-option-title">Per Sprache suchen</span>
                <span className="va-small va-muted">Namen sagen, wir finden die Unterkunft. Folgt.</span>
              </span>
            </div>
          </div>
        </div>
      )}

      {step === 'paste' && (
        <ConfirmationInput
          shared={shared}
          onSharedChange={setShared}
          text={text}
          onTextChange={setText}
          file={file}
          onFileChange={setFile}
          available={available}
          error={readError}
          onError={setReadError}
          onManual={manual}
        />
      )}

      {step === 'review' && (
        <>
          {source !== 'MANUAL' && (
            <p className="va-note">
              <span className="va-ai-mark">✦</span> aus der Bestätigung gelesen. <strong>Gelb markierte Felder bitte prüfen.</strong>
            </p>
          )}
          <AccommodationFields
            form={form}
            onChange={setForm}
            filled={filled}
            uncertain={uncertain}
            autoLocate={source !== 'MANUAL'}
            near={near}
          />
          {error && <Alert type="error" showIcon message={error} />}
        </>
      )}
    </Sheet>
  );

  function footer() {
    if (step === 'paste') {
      return (
        <button
          type="button"
          className="va-button va-button-accent va-block"
          disabled={reading || available === false || (!combinedEmpty)}
          onClick={() => void read()}
        >
          {reading ? 'Bestätigung wird gelesen …' : 'Auslesen'}
        </button>
      );
    }
    if (step === 'review') {
      return (
        <button type="button" className="va-button va-button-accent va-block" disabled={saving} onClick={() => void submit()}>
          {saving ? 'Wird gespeichert …' : (submitLabel ?? 'Buchung speichern')}
        </button>
      );
    }
    return undefined;
  }
}

