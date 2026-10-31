import { ArrowLeftOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';

import { endOfDayIso, startOfDayIso } from '../trips/time';
import { createTrip, uploadCoverImage } from '../trips/tripsApi';

const MAX_COVER_BYTES = 5 * 1024 * 1024;

/** "Neue Reise": name, period and title photo. Stops are added afterwards on the map. */
export default function NewTripPage() {
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [cover, setCover] = useState<'generate' | 'upload'>('generate');
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const startAt = startOfDayIso(from);
    const endAt = endOfDayIso(to);
    if (!name.trim() || !startAt || !endAt) {
      setError('Bitte Name, Beginn und Ende angeben.');
      return;
    }
    if (to < from) {
      setError('Das Ende liegt vor dem Beginn.');
      return;
    }
    if (cover === 'upload' && file && file.size > MAX_COVER_BYTES) {
      setError('Das Foto ist größer als 5 MB.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const trip = await createTrip({ name: name.trim(), startAt, endAt });
      if (cover === 'upload' && file) {
        await uploadCoverImage(trip.id, file).catch(() => undefined);
      }
      navigate(`/reisen/${trip.id}`, { replace: true });
    } catch {
      setError('Die Reise konnte nicht angelegt werden.');
      setSaving(false);
    }
  };

  return (
    <div className="va-page">
      <header className="va-page-header va-page-header-start">
        <Link to="/reisen" className="va-icon-button" aria-label="Abbrechen">
          <ArrowLeftOutlined />
        </Link>
        <h1 className="va-page-title va-page-title-small">Neue Reise</h1>
      </header>

      <form className="va-form" onSubmit={(event) => void submit(event)} noValidate>
        <div className="va-field">
          <label htmlFor="trip-name">Name der Reise</label>
          <input id="trip-name" type="text" value={name} maxLength={255} required onChange={(event) => setName(event.target.value)} />
        </div>
        <div className="va-field-row">
          <div className="va-field">
            <label htmlFor="trip-from">Von</label>
            <input id="trip-from" type="date" value={from} required onChange={(event) => setFrom(event.target.value)} />
          </div>
          <div className="va-field">
            <label htmlFor="trip-to">Bis</label>
            <input id="trip-to" type="date" value={to} min={from || undefined} required onChange={(event) => setTo(event.target.value)} />
          </div>
        </div>

        <fieldset className="va-fieldset">
          <legend>Titelfoto</legend>
          <label className={`va-option ${cover === 'generate' ? 'va-option-selected' : ''}`}>
            <input type="radio" name="cover" checked={cover === 'generate'} onChange={() => setCover('generate')} />
            <span>
              <span className="va-option-title">Automatisch erzeugen</span>
              <span className="va-muted">Passend zu Name und Zielen, später änderbar</span>
            </span>
          </label>
          <label className={`va-option ${cover === 'upload' ? 'va-option-selected' : ''}`}>
            <input type="radio" name="cover" checked={cover === 'upload'} onChange={() => setCover('upload')} />
            <span>
              <span className="va-option-title">Eigenes Foto hochladen</span>
              <span className="va-muted">Aus der Galerie oder mit der Kamera, JPEG, PNG oder WebP bis 5 MB</span>
            </span>
          </label>
          {cover === 'upload' && (
            <input
              aria-label="Foto auswählen"
              type="file"
              accept="image/jpeg,image/png,image/webp"
              onChange={(event) => setFile(event.target.files?.[0] ?? null)}
            />
          )}
        </fieldset>

        <p className="va-muted">Stops fügst du danach in der Reise auf der Karte hinzu.</p>
        {error && <Alert type="error" showIcon message={error} />}
        <div className="va-grow" />
        <button type="submit" className="va-button va-button-accent va-block" disabled={saving}>
          {saving ? 'Wird angelegt …' : 'Reise anlegen'}
        </button>
      </form>
    </div>
  );
}
