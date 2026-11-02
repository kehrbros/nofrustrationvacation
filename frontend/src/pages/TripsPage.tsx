import { PlusOutlined, WarningOutlined } from '@ant-design/icons';
import { Alert, Skeleton } from 'antd';
import { useState } from 'react';
import { Link } from 'react-router';

import { useAsync } from '../api/useAsync';
import ProfileButton from '../profile/ProfileButton';
import SharedBookingNote from '../bookings/SharedBookingNote';
import { InstallBanner } from '../pwa/InstallApp';
import CoverImage from '../trips/CoverImage';
import { groupTrips } from '../trips/grouping';
import type { TripSummary } from '../trips/model';
import { formatPeriod, tripDay, untilStart } from '../trips/time';
import { fetchTrips } from '../trips/tripsApi';

type Tab = 'upcoming' | 'past';

function stopsText(trip: TripSummary) {
  const stops = trip.stopCount === 1 ? '1 Stop' : `${trip.stopCount} Stops`;
  if (trip.phase === 'PAST') {
    return stops;
  }
  if (trip.incidentCount === 0) {
    return `${stops} · keine Hinweise`;
  }
  return `${stops} · ${trip.incidentCount === 1 ? '1 Hinweis' : `${trip.incidentCount} Hinweise`}`;
}

function IncidentBadge({ count }: { count: number }) {
  return (
    <span className="va-chip va-chip-soft">
      <WarningOutlined aria-hidden="true" />
      {count === 1 ? '1 Hinweis zur Route' : `${count} Hinweise zur Route`}
    </span>
  );
}

function CurrentTrip({ trip }: { trip: TripSummary }) {
  const { day, of } = tripDay(trip.startAt, trip.endAt);
  return (
    <Link to={`/reisen/${trip.id}`} className="va-card va-card-current">
      <CoverImage tripId={trip.id} hasCoverImage={trip.hasCoverImage} className="va-cover-wide" showHint />
      <div className="va-card-body">
        <div className="va-row va-space-between va-baseline">
          <span className="va-card-title-large">{trip.name}</span>
          <span className="va-chip va-chip-ink">
            Tag {Math.min(day, of)} von {of}
          </span>
        </div>
        <span className="va-muted">
          {formatPeriod(trip.startAt, trip.endAt)}
          {trip.nextStopName && ` · nächster Stop ${trip.nextStopName}`}
        </span>
        {trip.incidentCount > 0 && <IncidentBadge count={trip.incidentCount} />}
      </div>
    </Link>
  );
}

function TripRow({ trip }: { trip: TripSummary }) {
  const until = untilStart(trip.startAt);
  return (
    <Link to={`/reisen/${trip.id}`} className={`va-card va-card-row ${trip.phase === 'PAST' ? 'va-card-past' : ''}`}>
      <CoverImage tripId={trip.id} hasCoverImage={trip.hasCoverImage} className="va-cover-thumb" />
      <div className="va-card-body va-card-body-tight">
        <span className="va-card-title">{trip.name}</span>
        <span className="va-muted">
          {formatPeriod(trip.startAt, trip.endAt)}
          {until && ` · ${until}`}
        </span>
        <span className="va-small va-muted">{stopsText(trip)}</span>
        {trip.phase !== 'PAST' && trip.incidentCount > 0 && <IncidentBadge count={trip.incidentCount} />}
      </div>
    </Link>
  );
}

/** "Meine Reisen": current trip on top, below tabs with upcoming and past trips, each ascending by start. */
export default function TripsPage() {
  const { data, error, loading } = useAsync(fetchTrips, []);
  const [tab, setTab] = useState<Tab>('upcoming');
  const { current, upcoming, past } = groupTrips(data ?? []);
  const shown = tab === 'upcoming' ? upcoming : past;

  return (
    <div className="va-page">
      <header className="va-page-header">
        <h1 className="va-page-title">Meine Reisen</h1>
        <ProfileButton />
      </header>

      <SharedBookingNote />
      <InstallBanner />

      {error !== undefined && <Alert type="error" showIcon message="Die Reisen konnten nicht geladen werden." />}
      {loading && !data && <Skeleton active paragraph={{ rows: 6 }} />}

      {data && (
        <>
          {current && (
            <section aria-labelledby="current-title" className="va-section">
              <h2 id="current-title" className="va-eyebrow">
                Aktuelle Reise
              </h2>
              <CurrentTrip trip={current} />
            </section>
          )}

          <div role="tablist" aria-label="Weitere Reisen" className="va-tabs">
            <button type="button" role="tab" id="tab-upcoming" aria-controls="trip-list" aria-selected={tab === 'upcoming'} onClick={() => setTab('upcoming')}>
              Anstehende ({upcoming.length})
            </button>
            <button type="button" role="tab" id="tab-past" aria-controls="trip-list" aria-selected={tab === 'past'} onClick={() => setTab('past')}>
              Vergangene ({past.length})
            </button>
          </div>

          <div role="tabpanel" id="trip-list" aria-labelledby={`tab-${tab}`} className="va-list">
            {shown.map((trip) => (
              <TripRow key={trip.id} trip={trip} />
            ))}
            {shown.length === 0 && (
              <p className="va-empty">
                {tab === 'upcoming'
                  ? data.length === 0
                    ? 'Noch keine Reisen. Lege mit + deine erste Reise an.'
                    : 'Keine anstehenden Reisen.'
                  : 'Keine vergangenen Reisen.'}
              </p>
            )}
          </div>
        </>
      )}

      <Link to="/reisen/neu" className="va-fab" aria-label="Reise hinzufügen">
        <PlusOutlined />
      </Link>
    </div>
  );
}
