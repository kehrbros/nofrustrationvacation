import { ArrowLeftOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';

import KiwiLoader from '../components/KiwiLoader';
import { t } from '../i18n';
import { endOfDayIso, startOfDayIso } from '../trips/time';
import { createTrip, generateCoverImage, uploadCoverImage } from '../trips/tripsApi';

const MAX_COVER_BYTES = 5 * 1024 * 1024;

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

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const startAt = startOfDayIso(from);
    const endAt = endOfDayIso(to);
    if (!name.trim() || !startAt || !endAt) {
      setError(t('newTrip.missing'));
      return;
    }
    if (to < from) {
      setError(t('newTrip.endBeforeStart'));
      return;
    }
    if (travelers.trim() && !/^([1-9]|[1-9]\d)$/.test(travelers.trim())) {
      setError(t('newTrip.travelersInvalid'));
      return;
    }
    if (cover === 'upload' && file && file.size > MAX_COVER_BYTES) {
      setError(t('newTrip.photoTooLarge'));
      return;
    }
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
    } catch {
      setError(t('newTrip.createFailed'));
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
        <div className="va-field">
          <label htmlFor="trip-name">{t('newTrip.name')}</label>
          <input id="trip-name" type="text" value={name} maxLength={255} required onChange={(event) => setName(event.target.value)} />
        </div>
        <div className="va-field-row">
          <div className="va-field">
            <label htmlFor="trip-from">{t('newTrip.from')}</label>
            <input id="trip-from" type="date" value={from} required onChange={(event) => setFrom(event.target.value)} />
          </div>
          <div className="va-field">
            <label htmlFor="trip-to">{t('newTrip.to')}</label>
            <input id="trip-to" type="date" value={to} min={from || undefined} required onChange={(event) => setTo(event.target.value)} />
          </div>
        </div>
        <div className="va-field">
          <label htmlFor="trip-travelers">{t('newTrip.travelers')}</label>
          <input
            id="trip-travelers"
            type="number"
            min={1}
            max={99}
            inputMode="numeric"
            placeholder={t('newTrip.travelersPlaceholder')}
            value={travelers}
            onChange={(event) => setTravelers(event.target.value)}
          />
          <span className="va-small va-muted">{t('newTrip.travelersHint')}</span>
        </div>

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
            <input
              aria-label={t('cover.choose')}
              type="file"
              accept="image/jpeg,image/png,image/webp"
              onChange={(event) => setFile(event.target.files?.[0] ?? null)}
            />
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
