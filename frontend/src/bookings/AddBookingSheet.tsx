import { AudioOutlined, EditOutlined, SnippetsOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useEffect, useState } from 'react';

import KiwiLoader from '../components/KiwiLoader';
import Sheet from '../components/Sheet';
import { t } from '../i18n';
import Rich from '../i18n/Rich';
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
/** Thrown by onSubmit when the user cancelled saving, e.g. in a dialog of the caller; the form stays open. */
export class SubmitCancelled extends Error {
  constructor() {
    super('cancelled');
  }
}

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
    } catch (failure) {
      // cancelled by the caller (e.g. in its dialog): back to the form without a message
      setError(failure instanceof SubmitCancelled ? null : t('addBooking.saveFailed'));
      setSaving(false);
    }
  };

  const titles: Record<Step, string> = {
    choose: t('addBooking.title'),
    paste: t('addBooking.paste'),
    review: initialForm ? t('addBooking.edit') : t('addBooking.review'),
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
          {t('common.back')}
        </button>
      )}
      {step === 'choose' && (
        <div className="va-form">
          <div className="va-row va-gap va-wrap" role="group" aria-label={t('addBooking.kind')}>
            <span className="va-chip va-chip-ink" aria-current="true">{t('addBooking.accommodation')}</span>
            <span className="va-chip va-chip-off">{t('addBooking.transport')}</span>
            <span className="va-chip va-chip-off">{t('addBooking.activity')}</span>
          </div>
          <div className="va-choices">
            <button type="button" className="va-choice va-choice-main" onClick={() => setStep('paste')}>
              <SnippetsOutlined aria-hidden="true" />
              <span className="va-column">
                <span className="va-option-title">{t('addBooking.paste')}</span>
                <span className="va-small va-muted">
                  {shared ? t('addBooking.pasteShared') : t('addBooking.pasteHint')}
                </span>
              </span>
            </button>
            <button type="button" className="va-choice" onClick={manual}>
              <EditOutlined aria-hidden="true" />
              <span className="va-column">
                <span className="va-option-title">{t('addBooking.manual')}</span>
                <span className="va-small va-muted">{t('addBooking.manualHint')}</span>
              </span>
            </button>
            <div className="va-choice va-choice-off" aria-disabled="true">
              <AudioOutlined aria-hidden="true" />
              <span className="va-column">
                <span className="va-option-title">{t('addBooking.voice')}</span>
                <span className="va-small va-muted">{t('addBooking.voiceHint')}</span>
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
              <Rich text={t('addBooking.fromConfirmation')} />
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
          {reading ? t('addBooking.reading') : t('addBooking.read')}
        </button>
      );
    }
    if (step === 'review') {
      return (
        <button type="button" className="va-button va-button-accent va-block" disabled={saving} onClick={() => void submit()}>
          {saving && <><KiwiLoader size="small" decorative />{' '}</>}{saving ? t('common.saving') : (submitLabel ?? t('addBooking.save'))}
        </button>
      );
    }
    return undefined;
  }
}

