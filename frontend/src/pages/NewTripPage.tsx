import { ArrowLeftOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';

import Field, { RequiredNote } from '../components/form/Field';
import { failedField, useFieldErrors, type FieldErrors } from '../components/form/validation';
import KiwiLoader from '../components/KiwiLoader';
import { t } from '../i18n';
import { endOfDayIso, startOfDayIso } from '../trips/time';
import { createTrip, generateCoverImage, uploadCoverImage } from '../trips/tripsApi';

const MAX_COVER_BYTES = 5 * 1024 * 1024;

type TripField = 'name' | 'from' | 'to' | 'travelers' | 'photo';
const FIELDS: TripField[] = ['name', 'from', 'to', 'travelers', 'photo'];

interface TripFormValues {
  name: string;
  from: string;
  to: string;
  travelers: string;
  photo: File | null;
}

/** What is wrong with the new trip, per field (same rules as TripLogic). */
function tripErrors(values: TripFormValues): FieldErrors<TripField> {
  const errors: FieldErrors<TripField> = {};
  if (!values.name.trim()) {
    errors.name = t('newTrip.nameMissing');
  }
  if (!startOfDayIso(values.from)) {
    errors.from = t('newTrip.fromMissing');
  }
  if (!endOfDayIso(values.to)) {
    errors.to = t('newTrip.toMissing');
  } else if (values.from && values.to < values.from) {
    errors.to = t('newTrip.endBeforeStart');
  }
  if (values.travelers.trim() && !/^([1-9]|[1-9]\d)$/.test(values.travelers.trim())) {
    errors.travelers = t('newTrip.travelersInvalid');
  }
  if (values.photo && values.photo.size > MAX_COVER_BYTES) {
    errors.photo = t('newTrip.photoTooLarge');
  }
  return errors;
}

/** "Neue Reise": name, period and title photo. Stops are added afterwards on the map. */
export default function NewTripPage() {
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [travelers, setTravelers] = useState('');
  const [cover, setCover] = useState<'generate' | 'upload'>('generate');
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const values: TripFormValues = { name, from, to, travelers, photo: cover === 'upload' ? file : null };
  const validation = useFieldErrors(tripErrors(values), values);
  const { errors, touch } = validation;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!validation.check()) {
      return;
    }
    const startAt = startOfDayIso(from)!;
    const endAt = endOfDayIso(to)!;
    setSaving(true);
    setError(null);
    try {
      const trip = await createTrip({
        name: name.trim(),
        startAt,
        endAt,
        travelers: travelers.trim() ? Number(travelers) : null,
      });
      if (cover === 'upload' && file) {
        await uploadCoverImage(trip.id, file).catch(() => undefined);
      } else if (cover === 'generate') {
        // runs on while the trip opens; without assistant or on failure the placeholder stays
        void generateCoverImage(trip.id).catch(() => undefined);
      }
      navigate(`/reisen/${trip.id}`, { replace: true });
    } catch (failure) {
      const field = failedField(failure, FIELDS, { startAt: 'from', endAt: 'to' });
      if (field) {
        validation.refuse(field);
      }
      setError(field ? null : t('newTrip.createFailed'));
      setSaving(false);
    }
  };

  return (
    <div className="va-page">
      <header className="va-page-header va-page-header-start">
        <Link to="/reisen" className="va-icon-button" aria-label={t('common.cancel')}>
          <ArrowLeftOutlined />
        </Link>
        <h1 className="va-page-title va-page-title-small">{t('newTrip.title')}</h1>
      </header>

      <form className="va-form" onSubmit={(event) => void submit(event)} noValidate>
        <RequiredNote />
        <Field id="trip-name" label={t('newTrip.name')} required error={errors.name}>
          <input type="text" value={name} maxLength={255} onChange={(event) => setName(event.target.value)} onBlur={() => touch('name')} />
        </Field>
        <div className="va-field-row">
          <Field id="trip-from" label={t('newTrip.from')} required error={errors.from}>
            <input type="date" value={from} onChange={(event) => setFrom(event.target.value)} onBlur={() => touch('from')} />
          </Field>
          <Field id="trip-to" label={t('newTrip.to')} required error={errors.to}>
            <input type="date" value={to} min={from || undefined} onChange={(event) => setTo(event.target.value)} onBlur={() => touch('to')} />
          </Field>
        </div>
        <Field id="trip-travelers" label={t('newTrip.travelers')} error={errors.travelers} hint={t('newTrip.travelersHint')}>
          <input
            type="number"
            min={1}
            max={99}
            inputMode="numeric"
            placeholder={t('newTrip.travelersPlaceholder')}
            value={travelers}
            onChange={(event) => setTravelers(event.target.value)}
            onBlur={() => touch('travelers')}
          />
        </Field>

        <fieldset className="va-fieldset">
          <legend>{t('cover.legend')}</legend>
          <label className={`va-option ${cover === 'generate' ? 'va-option-selected' : ''}`}>
            <input type="radio" name="cover" checked={cover === 'generate'} onChange={() => setCover('generate')} />
            <span>
              <span className="va-option-title">{t('cover.generate')}</span>
              <span className="va-muted">{t('cover.generateHint')}</span>
            </span>
          </label>
          <label className={`va-option ${cover === 'upload' ? 'va-option-selected' : ''}`}>
            <input type="radio" name="cover" checked={cover === 'upload'} onChange={() => setCover('upload')} />
            <span>
              <span className="va-option-title">{t('cover.upload')}</span>
              <span className="va-muted">{t('cover.uploadHint')}</span>
            </span>
          </label>
          {cover === 'upload' && (
            <div className="va-column va-gap">
              <input
                aria-label={t('cover.choose')}
                type="file"
                accept="image/jpeg,image/png,image/webp"
                aria-invalid={errors.photo ? true : undefined}
                aria-describedby={errors.photo ? 'trip-photo-error' : undefined}
                onChange={(event) => {
                  setFile(event.target.files?.[0] ?? null);
                  touch('photo');
                }}
              />
              {errors.photo && <p id="trip-photo-error" className="va-field-error" role="alert">{errors.photo}</p>}
            </div>
          )}
        </fieldset>

        <p className="va-muted">{t('newTrip.stopsLater')}</p>
        {error && <Alert type="error" showIcon message={error} />}
        <div className="va-grow" />
        <button type="submit" className="va-button va-button-accent va-block" disabled={saving}>
          {saving && <><KiwiLoader size="small" decorative />{' '}</>}{saving ? t('newTrip.creating') : t('newTrip.create')}
        </button>
      </form>
    </div>
  );
}
