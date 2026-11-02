import { AudioOutlined, EditOutlined, FileTextOutlined, PaperClipOutlined, SnippetsOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useEffect, useState } from 'react';

import Sheet from '../components/Sheet';
import { TripsApiError } from '../trips/tripsApi';
import AccommodationFields from './AccommodationFields';
import { extractAccommodation, isExtractionAvailable } from './bookingsApi';
import { emptyForm, formFromDraft, validateForm, type AccommodationForm, type FormField } from './form';
import type { BookingSource } from './model';

type Step = 'choose' | 'paste' | 'bookingcom' | 'review';

interface Props {
  onClose: () => void;
  /** Saves (or hands over) the checked booking; a rejected promise keeps the sheet open with its message. */
  onSubmit: (form: AccommodationForm, source: BookingSource) => Promise<void>;
  /** Shown below the title, e.g. the stop. */
  context?: string;
  /** Edit an existing booking: starts with the filled form. */
  initialForm?: AccommodationForm;
  submitLabel?: string;
}

/**
 * "Buchung hinzufügen": first accommodations. Ways in: paste a confirmation (text, screenshot, PDF; read by the
 * assistant), hints for Booking.com, manual input. Every way ends in the same form the user checks before saving.
 */
export default function AddBookingSheet({ onClose, onSubmit, context, initialForm, submitLabel }: Props) {
  const [step, setStep] = useState<Step>(initialForm ? 'review' : 'choose');
  const [form, setForm] = useState<AccommodationForm>(initialForm ?? emptyForm());
  const [filled, setFilled] = useState<Set<FormField>>(new Set());
  const [uncertain, setUncertain] = useState<Set<FormField>>(new Set());
  const [source, setSource] = useState<BookingSource>('MANUAL');
  const [text, setText] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [reading, setReading] = useState(false);
  const [readError, setReadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [available, setAvailable] = useState<boolean | null>(null);

  useEffect(() => {
    void isExtractionAvailable().then(setAvailable);
  }, []);

  const manual = () => {
    setSource('MANUAL');
    setFilled(new Set());
    setUncertain(new Set());
    setStep('review');
  };

  const read = async () => {
    setReading(true);
    setReadError(null);
    try {
      const draft = await extractAccommodation(text, file);
      if (!draft.found) {
        setReadError('Darin haben wir keine Unterkunftsbuchung gefunden. Du kannst sie manuell eingeben.');
        return;
      }
      const result = formFromDraft(draft);
      setForm(result.form);
      setFilled(result.filled);
      setUncertain(result.uncertain);
      setSource('DOCUMENT');
      setStep('review');
    } catch (failure) {
      setReadError(readErrorMessage(failure));
    } finally {
      setReading(false);
    }
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
    } catch {
      setError('Die Buchung konnte nicht gespeichert werden.');
      setSaving(false);
    }
  };

  const titles: Record<Step, string> = {
    choose: 'Buchung hinzufügen',
    paste: 'Bestätigung einfügen',
    bookingcom: 'Buchung von Booking.com',
    review: initialForm ? 'Buchung bearbeiten' : 'Buchung prüfen',
  };
  const back = step === 'choose' || initialForm ? undefined : () => setStep(step === 'review' && source === 'DOCUMENT' ? 'paste' : 'choose');

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
                <span className="va-small va-muted">Mail-Text, Screenshot oder PDF. Wir lesen Unterkunft, Zeitraum und Buchungsnummer aus.</span>
              </span>
            </button>
            <button type="button" className="va-choice" onClick={() => setStep('bookingcom')}>
              <span className="va-choice-logo" aria-hidden="true">B.</span>
              <span className="va-column">
                <span className="va-option-title">Booking.com</span>
                <span className="va-small va-muted">Mit Buchungsnummer allein geht es leider nicht. So klappt es trotzdem schnell.</span>
              </span>
            </button>
            <button type="button" className="va-choice" onClick={manual}>
              <EditOutlined aria-hidden="true" />
              <span className="va-column">
                <span className="va-option-title">Manuell eingeben</span>
                <span className="va-small va-muted">Name, Zeitraum und Buchungsnummer selbst eintragen.</span>
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

      {step === 'bookingcom' && (
        <div className="va-form">
          <p className="va-muted">
            Booking.com bietet keine Schnittstelle, über die wir eine Buchung mit Buchungsnummer und PIN abrufen dürfen.
            Die Bestätigung enthält aber alles, was wir brauchen:
          </p>
          <ol className="va-steps-list">
            <li>
              <strong>Bestätigungsmail</strong> von Booking.com öffnen, den Text markieren und kopieren.{' '}
              <span className="va-muted">Oder in der Booking.com-App die Buchung öffnen und einen Screenshot machen bzw. die Bestätigung als PDF sichern.</span>
            </li>
            <li>Hier einfügen oder die Datei wählen. Wir lesen Unterkunft, Zeitraum, Preis und Buchungsnummer aus.</li>
            <li>Prüfen und speichern.</li>
          </ol>
          <p className="va-note">
            <strong>Tipp für Android:</strong> In der Mail-App oder bei Booking.com auf „Teilen“ tippen und NoFrustration. wählen
            (die App muss installiert sein). <strong>Die PIN brauchen wir nicht</strong> und speichern sie nicht.
          </p>
        </div>
      )}

      {step === 'paste' && (
        <div className="va-form">
          {available === false && (
            <Alert
              type="info"
              showIcon
              message="Automatisches Auslesen ist auf diesem Server nicht eingerichtet."
              description="Du kannst die Buchung manuell eingeben."
            />
          )}
          <div className="va-field">
            <label htmlFor="booking-text">Text der Bestätigung</label>
            <textarea
              id="booking-text"
              rows={7}
              placeholder="Text der Bestätigungsmail hier einfügen …"
              value={text}
              onChange={(event) => setText(event.target.value)}
            />
            {typeof navigator !== 'undefined' && navigator.clipboard?.readText && (
              <button
                type="button"
                className="va-text-button"
                onClick={() => {
                  navigator.clipboard.readText().then((pasted) => pasted && setText(pasted), () => undefined);
                }}
              >
                <SnippetsOutlined aria-hidden="true" /> Aus Zwischenablage einfügen
              </button>
            )}
          </div>
          <div className="va-divider" role="separator"><span>oder</span></div>
          <div className="va-field">
            <span className="va-label">Screenshot oder PDF</span>
            {file ? (
              <span className="va-file">
                {file.type === 'application/pdf' ? <FileTextOutlined aria-hidden="true" /> : <PaperClipOutlined aria-hidden="true" />}
                <span className="va-grow va-ellipsis">{file.name}</span>
                <button type="button" className="va-text-button" onClick={() => setFile(null)}>entfernen</button>
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
                    if (chosen && chosen.size > 5 * 1024 * 1024) {
                      setReadError('Die Datei ist größer als 5 MB.');
                      return;
                    }
                    setReadError(null);
                    setFile(chosen);
                  }}
                />
              </label>
            )}
          </div>
          <p className="va-small va-muted">
            Wird zur Auswertung an OpenAI übertragen und nicht gespeichert. PIN, Zahlungs- und Gästedaten übernehmen wir nicht.
          </p>
          {readError && (
            <Alert
              type="warning"
              showIcon
              message={readError}
              action={<button type="button" className="va-text-button" onClick={manual}>Manuell eingeben</button>}
            />
          )}
        </div>
      )}

      {step === 'review' && (
        <>
          {source === 'DOCUMENT' && (
            <p className="va-note">
              <span className="va-ai-mark">✦</span> aus der Bestätigung gelesen. <strong>Gelb markierte Felder bitte prüfen.</strong>
            </p>
          )}
          <AccommodationFields form={form} onChange={setForm} filled={filled} uncertain={uncertain} autoLocate={source === 'DOCUMENT'} />
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
          disabled={reading || available === false || (!text.trim() && !file)}
          onClick={() => void read()}
        >
          {reading ? 'Bestätigung wird gelesen …' : 'Auslesen'}
        </button>
      );
    }
    if (step === 'bookingcom') {
      return (
        <button type="button" className="va-button va-button-accent va-block" onClick={() => setStep('paste')}>
          Bestätigung einfügen
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

function readErrorMessage(failure: unknown): string {
  if (failure instanceof TripsApiError) {
    if (failure.code === 'ASSISTANT_RATE_LIMITED') {
      return 'Gerade zu viele Anfragen. Bitte später noch einmal versuchen oder manuell eingeben.';
    }
    if (failure.code === 'INVALID_BOOKING_INPUT') {
      return 'Diese Datei können wir nicht lesen. Bitte Text, ein Bild (JPEG, PNG, WebP) oder ein PDF bis 5 MB verwenden.';
    }
    if (failure.status === 413) {
      return 'Die Datei ist zu groß (höchstens 5 MB).';
    }
  }
  return 'Die Bestätigung konnte gerade nicht gelesen werden. Du kannst die Buchung manuell eingeben.';
}
