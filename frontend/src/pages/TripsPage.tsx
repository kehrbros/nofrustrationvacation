import { PlusOutlined, WarningOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useState } from 'react';
import { Link } from 'react-router';

import KiwiLoader from '../components/KiwiLoader';
import { t } from '../i18n';
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
  const stops = t('common.stops', { count: trip.stopCount });
  if (trip.phase === 'PAST') {
    return stops;
  }
  if (trip.incidentCount === 0) {
    return `${stops} · ${t('trips.noHints')}`;
  }
  return `${stops} · ${t('common.hints', { count: trip.incidentCount })}`;
}

function IncidentBadge({ count }: { count: number }) {
  return (
    <span className="va-chip va-chip-soft">
      <WarningOutlined aria-hidden="true" />
      {t('trips.routeHints', { count })}
    </span>
  );
}

function CurrentTrip({ trip }: { trip: TripSummary }) {
  const { day, of } = tripDay(trip.startAt, trip.endAt);
  return (
    <Link to={`/reisen/${trip.id}`} className="va-card va-card-current">
      <CoverImage tripId={trip.id} hasCoverImage={trip.hasCoverImage} className="va-cover-wide" size="thumbnail" showHint />
      <div className="va-card-body">
        <div className="va-row va-space-between va-baseline">
          <span className="va-card-title-large">{trip.name}</span>
          <span className="va-chip va-chip-ink">
            {t('trips.dayOf', { day: Math.min(day, of), of })}
          </span>
        </div>
        <span className="va-muted">
          {formatPeriod(trip.startAt, trip.endAt)}
          {trip.nextStopName && ` · ${t('trips.nextStop', { name: trip.nextStopName })}`}
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
      <CoverImage tripId={trip.id} hasCoverImage={trip.hasCoverImage} className="va-cover-thumb" size="thumbnail" />
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
        <h1 className="va-page-title">{t('trips.title')}</h1>
        <ProfileButton />
      </header>

      <SharedBookingNote />
      <InstallBanner />

      {error !== undefined && <Alert type="error" showIcon message={t('trips.loadFailed')} />}
      {loading && !data && <div className="va-loading-block"><KiwiLoader size="large" /></div>}

      {data && (
        <>
          {current && (
            <section aria-labelledby="current-title" className="va-section">
              <h2 id="current-title" className="va-eyebrow">
                {t('trips.current')}
              </h2>
              <CurrentTrip trip={current} />
            </section>
          )}

          <div role="tablist" aria-label={t('trips.more')} className="va-tabs">
            <button type="button" role="tab" id="tab-upcoming" aria-controls="trip-list" aria-selected={tab === 'upcoming'} onClick={() => setTab('upcoming')}>
              {t('trips.upcoming', { count: upcoming.length })}
            </button>
            <button type="button" role="tab" id="tab-past" aria-controls="trip-list" aria-selected={tab === 'past'} onClick={() => setTab('past')}>
              {t('trips.past', { count: past.length })}
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
                    ? t('trips.empty')
                    : t('trips.noUpcoming')
                  : t('trips.noPast')}
              </p>
            )}
          </div>
        </>
      )}

      <Link to="/reisen/neu" className="va-fab" aria-label={t('trips.add')}>
        <PlusOutlined />
      </Link>
    </div>
  );
}
