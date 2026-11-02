import { ArrowLeftOutlined, HomeOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Spin } from 'antd';
import { lazy, Suspense, useCallback, useMemo, useState } from 'react';
import { Link, Outlet, useNavigate, useParams } from 'react-router';

import { useAsync } from '../api/useAsync';
import { fetchTripBookings } from '../bookings/bookingsApi';
import type { Booking } from '../bookings/model';
import { stayLabel } from '../bookings/text';
import ProfileButton from '../profile/ProfileButton';
import { impactLabel, incidentTitle } from '../trips/incidentText';
import type { TripIncident } from '../trips/model';
import { routeName, type TripContext } from '../trips/tripContext';
import { formatDateTime, formatPeriod } from '../trips/time';
import { fetchTrip, fetchTripIncidents, TripsApiError } from '../trips/tripsApi';

// MapLibre needs the browser (WebGL), load it only there
const TripMap = lazy(() => import('../map/TripMap'));

const SHEET_COLLAPSED = 170;

/** Trip detail: map with stops, routes and incidents, below a sheet with the list; dialogs as child routes. */
export default function TripPage() {
  const { tripId } = useParams();
  const id = Number(tripId);
  const navigate = useNavigate();
  const { data, error, reload } = useAsync(
    // bookings are optional for the page: without them the trip still shows
    () => Promise.all([fetchTrip(id), fetchTripIncidents(id), fetchTripBookings(id).catch(() => [] as Booking[])]),
    [id],
  );
  const [expanded, setExpanded] = useState(false);
  const [trip, incidents, bookings] = data ?? [undefined, [] as TripIncident[], [] as Booking[]];

  const stopById = useMemo(() => new Map((trip?.stops ?? []).map((stop) => [stop.id, stop])), [trip]);
  const bookingsByStop = useMemo(() => {
    const byStop = new Map<number, Booking[]>();
    bookings.forEach((booking) => byStop.set(booking.stopId, [...(byStop.get(booking.stopId) ?? []), booking]));
    return byStop;
  }, [bookings]);
  const incidentsByRoute = useMemo(() => {
    const byRoute = new Map<number, TripIncident[]>();
    incidents.forEach((incident) => byRoute.set(incident.routeId, [...(byRoute.get(incident.routeId) ?? []), incident]));
    return byRoute;
  }, [incidents]);
  const close = useCallback(() => navigate(`/reisen/${id}`), [navigate, id]);
  const openIncident = useCallback(
    (incident: TripIncident) => navigate(`/reisen/${id}/hinweise/${incident.routeId}/${incident.incidentId}`),
    [navigate, id],
  );
  const openRoute = useCallback((routeId: number) => navigate(`/reisen/${id}/routen/${routeId}`), [navigate, id]);

  if (error !== undefined) {
    const notFound = error instanceof TripsApiError && error.status === 404;
    return (
      <div className="va-page">
        <Alert type="error" showIcon message={notFound ? 'Diese Reise gibt es nicht.' : 'Die Reise konnte nicht geladen werden.'} />
        <Link to="/reisen">Zurück zu Meine Reisen</Link>
      </div>
    );
  }
  if (!trip) {
    return <Spin fullscreen />;
  }

  const context: TripContext = { trip, incidents, incidentsByRoute, stopById, bookings, bookingsByStop, reload, close };
  const stopCount = trip.stops.length;
  // routes in the order of the trip, affected ones first while collapsed
  const routes = [...trip.routes].sort(
    (a, b) => (stopById.get(a.fromStopId)?.position ?? 0) - (stopById.get(b.fromStopId)?.position ?? 0),
  );
  const collapsedRoutes = [...routes].sort((a, b) => (incidentsByRoute.has(b.id) ? 1 : 0) - (incidentsByRoute.has(a.id) ? 1 : 0));

  return (
    <div className="va-trip">
      <header className="va-trip-header">
        <Link to="/reisen" className="va-icon-button" aria-label="Zurück zu Meine Reisen">
          <ArrowLeftOutlined />
        </Link>
        <div className="va-trip-titles">
          <h1 className="va-trip-title">{trip.name}</h1>
          <span className="va-muted">{formatPeriod(trip.startAt, trip.endAt)}</span>
        </div>
        <ProfileButton />
      </header>

      <div className="va-trip-map">
        <Suspense fallback={<div className="va-map va-map-loading" />}>
          <TripMap
            stops={trip.stops}
            routes={trip.routes}
            incidentsByRoute={incidentsByRoute}
            onRouteClick={openRoute}
            onIncidentClick={openIncident}
            bottomPadding={SHEET_COLLAPSED}
          />
        </Suspense>
        {!expanded && (
          <Link to="stops/neu" className="va-fab va-fab-map" aria-label="Stop hinzufügen">
            <PlusOutlined />
          </Link>
        )}
      </div>

      <section aria-label="Stops und Hinweise" className={`va-trip-sheet ${expanded ? 'va-trip-sheet-expanded' : ''}`}>
        <button
          type="button"
          className="va-trip-sheet-toggle"
          aria-expanded={expanded}
          onClick={() => setExpanded((value) => !value)}
        >
          <span className="va-sheet-grip" aria-hidden="true" />
          <span className="va-row va-space-between va-baseline va-full">
            <span className="va-sheet-summary">
              {stopCount === 1 ? '1 Stop' : `${stopCount} Stops`} · {incidents.length === 1 ? '1 Hinweis' : `${incidents.length} Hinweise`}
            </span>
            <span className="va-muted">{expanded ? 'Karte ↓' : 'Liste ↑'}</span>
          </span>
        </button>

        {stopCount === 0 && <p className="va-empty">Noch keine Stops. Füge mit + den ersten Stop hinzu.</p>}

        {!expanded &&
          collapsedRoutes.slice(0, 1).map((route) => <RouteRow key={route.id} context={context} routeId={route.id} />)}

        {expanded && (
          <ol className="va-stop-list">
            {trip.stops.map((stop, index) => {
              const next = routes.find((route) => route.fromStopId === stop.id);
              return (
                <li key={stop.id}>
                  <div className="va-row va-gap">
                    <span className="va-stop-number">{index + 1}</span>
                    <span className="va-column">
                      <span className="va-strong">{stop.name}</span>
                      <span className="va-small va-muted">
                        {[stop.arrivalAt && `an ${formatDateTime(stop.arrivalAt)}`, stop.departureAt && `ab ${formatDateTime(stop.departureAt)}`]
                          .filter(Boolean)
                          .join(' · ') || 'ohne Zeiten'}
                        {stop.latitude == null && ' · nicht auf der Karte'}
                      </span>
                    </span>
                  </div>
                  <StopBookings bookings={bookingsByStop.get(stop.id) ?? []} stopId={stop.id} />
                  {next && <RouteRow context={context} routeId={next.id} />}
                </li>
              );
            })}
          </ol>
        )}
      </section>

      <Outlet context={context} />
    </div>
  );
}

/** Bookings below a stop in the list, each opens its dialog; "+ Buchung" adds one to the stop. */
function StopBookings({ bookings, stopId }: { bookings: Booking[]; stopId: number }) {
  return (
    <div className="va-stop-bookings">
      {bookings.map((booking) => (
        <Link key={booking.id} to={`buchungen/${booking.id}`} className="va-booking-row" aria-label={`Buchung ${booking.title} ansehen`}>
          <span className="va-booking-icon" aria-hidden="true"><HomeOutlined /></span>
          <span className="va-column va-grow">
            <span className="va-strong">{booking.title}</span>
            <span className="va-small va-muted">{stayLabel(booking)}{booking.provider && ` · ${booking.provider}`}</span>
          </span>
        </Link>
      ))}
      <Link to={`stops/${stopId}/buchung`} className="va-text-button va-add-booking">
        + Buchung
      </Link>
    </div>
  );
}

function RouteRow({ context, routeId }: { context: TripContext; routeId: number }) {
  const route = context.trip.routes.find((candidate) => candidate.id === routeId)!;
  const from = context.stopById.get(route.fromStopId);
  const incident = context.incidentsByRoute.get(route.id)?.[0];
  return (
    <Link to={`routen/${route.id}`} className="va-route-row" aria-label={`Route ${routeName(route, context.stopById)} ansehen`}>
      <span className="va-column va-grow">
        <span className="va-strong">{routeName(route, context.stopById)}</span>
        <span className="va-small va-muted">
          {from?.departureAt ? `Abfahrt ${formatDateTime(from.departureAt)}` : 'Abfahrt offen'}
          {incident && ` · ${incidentTitle(incident)}`}
        </span>
      </span>
      {incident && <span className="va-chip va-chip-soft">{impactLabel(incident.impact)}</span>}
    </Link>
  );
}
