import { ArrowLeftOutlined, DeleteOutlined, HomeOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Popconfirm, Spin } from 'antd';
import { lazy, Suspense, useCallback, useMemo, useState } from 'react';
import { Link, Outlet, useNavigate, useParams } from 'react-router';

import { useAsync } from '../api/useAsync';
import { fetchTripBookings } from '../bookings/bookingsApi';
import type { Booking } from '../bookings/model';
import { stayLabel } from '../bookings/text';
import ProfileButton from '../profile/ProfileButton';
import { stopDeletionText, tripDeletionText } from '../trips/deletion';
import { impactLabel, incidentTitle } from '../trips/incidentText';
import type { RouteLine, TripIncident } from '../trips/model';
import { formatDuration } from '../trips/routeOptionText';
import { fetchRouteLines } from '../trips/routesApi';
import { routeName, type RoutePreview, type TripContext } from '../trips/tripContext';
import { formatDateTime, formatPeriod } from '../trips/time';
import { deleteTrip, fetchTrip, fetchTripIncidents, removeStop, TripsApiError } from '../trips/tripsApi';

// MapLibre needs the browser (WebGL), load it only there
const TripMap = lazy(() => import('../map/TripMap'));

const SHEET_COLLAPSED = 170;

/** Trip detail: map with stops, routes and incidents, below a sheet with the list; dialogs as child routes. */
export default function TripPage() {
  const { tripId } = useParams();
  const id = Number(tripId);
  const navigate = useNavigate();
  const { data, error, reload } = useAsync(
    // bookings and route lines are optional for the page: without them the trip still shows
    () =>
      Promise.all([
        fetchTrip(id),
        fetchTripIncidents(id),
        fetchTripBookings(id).catch(() => [] as Booking[]),
        fetchRouteLines(id).catch(() => [] as RouteLine[]),
      ]),
    [id],
  );
  const [expanded, setExpanded] = useState(false);
  const [routePreview, setRoutePreview] = useState<RoutePreview | null>(null);
  const [trip, incidents, bookings, lines] = data ?? [undefined, [] as TripIncident[], [] as Booking[], [] as RouteLine[]];
  const routeLines = useMemo(() => new Map(lines.map((line) => [line.routeId, line])), [lines]);

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
  const openAlternatives = useCallback(
    (consequence: TripIncident) => navigate(`/reisen/${id}/alternativen/${consequence.routeId}/${consequence.incidentId}`),
    [navigate, id],
  );
  const openRoute = useCallback((routeId: number) => navigate(`/reisen/${id}/routen/${routeId}`), [navigate, id]);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const deleteStop = async (stopId: number) => {
    setDeleteError(null);
    try {
      await removeStop(id, stopId);
      reload();
    } catch (failure) {
      setDeleteError(
        failure instanceof TripsApiError && failure.code === 'STOP_IN_USE'
          ? 'Am Stop wird gerade eine Unterkunft gebucht. Bitte versuche es gleich noch einmal.'
          : 'Der Stop konnte nicht gelöscht werden.',
      );
    }
  };
  const removeTrip = async () => {
    setDeleteError(null);
    try {
      await deleteTrip(id);
      navigate('/reisen');
    } catch (failure) {
      setDeleteError(
        failure instanceof TripsApiError && failure.code === 'STOP_IN_USE'
          ? 'In der Reise wird gerade eine Unterkunft gebucht. Bitte versuche es gleich noch einmal.'
          : 'Die Reise konnte nicht gelöscht werden.',
      );
    }
  };

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

  const context: TripContext = {
    trip,
    incidents,
    incidentsByRoute,
    stopById,
    bookings,
    bookingsByStop,
    routeLines,
    setRoutePreview,
    reload,
    close,
  };
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
        <Popconfirm
          title="Reise löschen?"
          description={tripDeletionText(trip, bookings)}
          okText="Löschen"
          okButtonProps={{ danger: true }}
          cancelText="Abbrechen"
          placement="bottomRight"
          onConfirm={() => removeTrip()}
        >
          <button type="button" className="va-icon-button" aria-label="Reise löschen">
            <DeleteOutlined />
          </button>
        </Popconfirm>
        <ProfileButton />
      </header>

      <div className="va-trip-map">
        <Suspense fallback={<div className="va-map va-map-loading" />}>
          <TripMap
            stops={trip.stops}
            routes={trip.routes}
            routeLines={routeLines}
            preview={routePreview}
            incidentsByRoute={incidentsByRoute}
            onRouteClick={openRoute}
            onConsequenceClick={openAlternatives}
            bottomPadding={SHEET_COLLAPSED}
          />
        </Suspense>
      </div>

      {/* the dock keeps the add button right above the sheet, collapsed or expanded */}
      <div className="va-trip-dock">
        <Link to="stops/neu" className="va-fab va-fab-dock" aria-label="Stop hinzufügen">
          <PlusOutlined />
        </Link>
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

          {deleteError && <Alert type="error" showIcon closable message={deleteError} onClose={() => setDeleteError(null)} />}

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
                      <span className="va-column va-grow">
                        <span className="va-strong">{stop.name}</span>
                        <span className="va-small va-muted">
                          {[stop.arrivalAt && `an ${formatDateTime(stop.arrivalAt)}`, stop.departureAt && `ab ${formatDateTime(stop.departureAt)}`]
                            .filter(Boolean)
                            .join(' · ') || 'ohne Zeiten'}
                          {stop.latitude == null && ' · nicht auf der Karte'}
                        </span>
                      </span>
                      <Popconfirm
                        title={`${stop.name} löschen?`}
                        description={stopDeletionText(trip, stop.id, bookingsByStop.get(stop.id) ?? [])}
                        okText="Löschen"
                        okButtonProps={{ danger: true }}
                        cancelText="Abbrechen"
                        placement="topRight"
                        onConfirm={() => deleteStop(stop.id)}
                      >
                        <button type="button" className="va-icon-button va-plain" aria-label={`Stop ${stop.name} löschen`}>
                          <DeleteOutlined />
                        </button>
                      </Popconfirm>
                    </div>
                    <StopBookings bookings={bookingsByStop.get(stop.id) ?? []} stopId={stop.id} />
                    {next && <RouteRow context={context} routeId={next.id} />}
                  </li>
                );
              })}
            </ol>
          )}
        </section>
      </div>

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
  const line = context.routeLines.get(route.id);
  return (
    <Link to={`routen/${route.id}`} className="va-route-row" aria-label={`Route ${routeName(route, context.stopById)} ansehen`}>
      <span className="va-column va-grow">
        <span className="va-strong">{routeName(route, context.stopById)}</span>
        <span className="va-small va-muted">
          {[
            line?.via && `über ${line.via}`,
            line && formatDuration(line.durationSeconds),
            from?.departureAt ? `Abfahrt ${formatDateTime(from.departureAt)}` : 'Abfahrt offen',
            incident && incidentTitle(incident),
          ]
            .filter(Boolean)
            .join(' · ')}
        </span>
      </span>
      {incident && <span className="va-chip va-chip-soft">{impactLabel(incident.impact)}</span>}
    </Link>
  );
}
