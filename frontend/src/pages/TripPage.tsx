import { AimOutlined, ArrowLeftOutlined, DeleteOutlined, EditOutlined, HomeOutlined, NodeIndexOutlined, PlusOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { Alert, Popconfirm, Spin } from 'antd';
import { lazy, Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Outlet, useNavigate, useParams } from 'react-router';

import KiwiLoader from '../components/KiwiLoader';
import { t } from '../i18n';
import { useAsync } from '../api/useAsync';
import { hasRole } from '../auth/session';
import { fetchTripBookings } from '../bookings/bookingsApi';
import type { Booking } from '../bookings/model';
import { stayLabel } from '../bookings/text';
import { isAppBooked } from '../bookings/appBookings';
import OpenReservations from '../bookings/OpenReservations';
import RemoveAppBookingDialog from '../bookings/RemoveAppBookingDialog';
import TodoList from '../bookings/TodoList';
import type { MapFocus } from '../map/TripMap';
import { useCurrentPosition } from '../map/useCurrentPosition';
import ProposalsBanner from '../proposals/ProposalsBanner';
import ProfileButton from '../profile/ProfileButton';
import { stopDeletionText, tripDeletionText } from '../trips/deletion';
import { incidentIcon } from '../trips/incidentIcon';
import { impactLabel, incidentTypeLabel } from '../trips/incidentText';
import type { LinePoint, RouteLine, TripIncident } from '../trips/model';
import { formatDuration } from '../trips/routeOptionText';
import { fetchRouteLines } from '../trips/routesApi';
import { routeName, type RoutePreview, type TripContext } from '../trips/tripContext';
import { formatPeriod, stopDays } from '../trips/time';
import { deleteTrip, dismissIncident, fetchTrip, fetchTripIncidents, removeStop, TripsApiError } from '../trips/tripsApi';

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
  // picking a point on the map for a dialog (test incident); the function is kept in an object, React would call it
  type PointPicker = (point: LinePoint, metersPerPixel: number) => void;
  const [pointPicker, setPointPickerState] = useState<{ onPick: PointPicker } | null>(null);
  const setPointPicker = useCallback((onPick: PointPicker | null) => setPointPickerState(onPick ? { onPick } : null), []);
  const [pickedPoint, setPickedPoint] = useState<LinePoint | null>(null);
  // test incidents only for the role tester, the endpoint needs it (Docs/traffic-feed.md#test-source-manual)
  const [tester, setTester] = useState(false);
  useEffect(() => {
    let active = true;
    void hasRole('tester').then((value) => active && setTester(value));
    return () => {
      active = false;
    };
  }, []);
  const position = useCurrentPosition();
  const [focus, setFocus] = useState<MapFocus | null>(null);
  // short note next to the map buttons, e.g. why the position button cannot help or a removal that failed
  const [dockHint, setDockHint] = useState<string | null>(null);
  useEffect(() => {
    if (!dockHint) {
      return;
    }
    const timer = setTimeout(() => setDockHint(null), 5000);
    return () => clearTimeout(timer);
  }, [dockHint]);
  const focusOn = (target: MapFocus['target']) => setFocus((last) => ({ target, key: (last?.key ?? 0) + 1 }));
  const focusPosition = () => {
    if (position.coordinates || position.status === 'searching') {
      setDockHint(position.coordinates ? null : t('map.position.searching'));
      focusOn('position');
    } else {
      setDockHint(t(`map.position.${position.status === 'denied' ? 'denied' : 'unavailable'}`));
    }
  };
  const [trip, allIncidents, bookings, lines] = data ?? [undefined, [] as TripIncident[], [] as Booking[], [] as RouteLine[]];
  // resolved ones (incident ended) only appear on the map with a green check, everything else uses the current ones
  const incidents = useMemo(() => allIncidents.filter((incident) => !incident.resolved), [allIncidents]);
  // removed by the user: gone from the map at once, back if the server refuses (Docs/domain.md, erledigte Hinweise)
  const [removedResolved, setRemovedResolved] = useState<ReadonlySet<string>>(() => new Set());
  const resolvedIncidents = useMemo(
    () => allIncidents.filter((incident) => incident.resolved && !removedResolved.has(incidentKey(incident))),
    [allIncidents, removedResolved],
  );
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
  const openResolved = useCallback(
    (consequence: TripIncident) => navigate(`/reisen/${id}/hinweise/${consequence.routeId}/${consequence.incidentId}`),
    [navigate, id],
  );
  // a tap on the map above the expanded list folds it back in, like the toggle
  const collapse = useCallback(() => setExpanded(false), []);
  // a route hit by an incident opens its solutions, like the chip and the row in the list; others the route sheet
  const openRoute = useCallback(
    (routeId: number) => {
      const incident = incidentsByRoute.get(routeId)?.[0];
      navigate(incident ? `/reisen/${id}/alternativen/${routeId}/${incident.incidentId}` : `/reisen/${id}/routen/${routeId}`);
    },
    [navigate, id, incidentsByRoute],
  );
  const removeResolvedIncident = useCallback(
    (incident: TripIncident) => {
      const key = incidentKey(incident);
      const setRemoved = (removed: boolean) =>
        setRemovedResolved((last) => {
          const next = new Set(last);
          if (removed) {
            next.add(key);
          } else {
            next.delete(key);
          }
          return next;
        });
      setRemoved(true);
      dismissIncident(id, incident.routeId, incident.incidentId).then(
        () => reload(),
        () => {
          setRemoved(false);
          setDockHint(t('incidentSheet.removeFailed'));
        },
      );
    },
    [id, reload],
  );
  const [deleteError, setDeleteError] = useState<string | null>(null);
  // deleting a stop with bookings made and paid in the app: ask whether to cancel them as well
  const [stopToDelete, setStopToDelete] = useState<number | null>(null);
  const deleteStop = async (stopId: number) => {
    setDeleteError(null);
    try {
      await removeStop(id, stopId);
      reload();
    } catch (failure) {
      setDeleteError(
        failure instanceof TripsApiError && failure.code === 'STOP_IN_USE'
          ? t('trip.stopInUse')
          : t('trip.stopDeleteFailed'),
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
          ? t('trip.tripInUse')
          : t('trip.tripDeleteFailed'),
      );
    }
  };

  if (error !== undefined) {
    const notFound = error instanceof TripsApiError && error.status === 404;
    return (
      <div className="va-page">
        <Alert type="error" showIcon message={notFound ? t('trip.notFound') : t('trip.loadFailed')} />
        <Link to="/reisen">{t('trip.backToTrips')}</Link>
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
    resolvedIncidents,
    removeResolvedIncident,
    stopById,
    bookings,
    bookingsByStop,
    routeLines,
    setRoutePreview,
    setPointPicker,
    setPickedPoint,
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
        <Link to="/reisen" className="va-icon-button" aria-label={t('trip.backToTrips')}>
          <ArrowLeftOutlined />
        </Link>
        <div className="va-trip-titles">
          <h1 className="va-trip-title">{trip.name}</h1>
          <span className="va-muted">{formatPeriod(trip.startAt, trip.endAt)}</span>
        </div>
        <Popconfirm
          title={t('trip.deleteTrip')}
          description={tripDeletionText(trip, bookings)}
          okText={t('common.delete')}
          okButtonProps={{ danger: true }}
          cancelText={t('common.cancel')}
          placement="bottomRight"
          onConfirm={() => removeTrip()}
        >
          <button type="button" className="va-icon-button" aria-label={t('trip.deleteTripLabel')}>
            <DeleteOutlined />
          </button>
        </Popconfirm>
        <ProfileButton />
      </header>

      <div className="va-trip-map">
        <div className="va-trip-banners">
          <OpenReservations tripId={trip.id} bookings={bookings} onFinished={reload} />
          <ProposalsBanner trip={trip} incidentsByRoute={incidentsByRoute} stopById={stopById} />
        </div>
        <Suspense fallback={<div className="va-map va-map-loading"><KiwiLoader size="large" /></div>}>
          <TripMap
            stops={trip.stops}
            routes={trip.routes}
            routeLines={routeLines}
            preview={routePreview}
            incidentsByRoute={incidentsByRoute}
            resolvedIncidents={resolvedIncidents}
            onRouteClick={openRoute}
            onConsequenceClick={openAlternatives}
            onResolvedClick={openResolved}
            bottomPadding={SHEET_COLLAPSED}
            onMapClick={collapse}
            onPickPoint={pointPicker?.onPick ?? null}
            pickedPoint={pickedPoint}
            position={position}
            focus={focus}
          />
        </Suspense>
      </div>

      {/* the dock keeps the add button right above the sheet, collapsed or expanded */}
      <div className="va-trip-dock">
        <div className="va-dock-actions">
          {tester && (
            <Link to="stoerungen/neu" className="va-fab va-fab-dock va-fab-incident" aria-label={t('testIncident.open')} title={t('testIncident.open')}>
              <ThunderboltOutlined />
              <span className="va-fab-label" aria-hidden="true">{t('testIncident.label')}</span>
            </Link>
          )}
          {dockHint && (
            <span className="va-dock-hint" role="status">
              {dockHint}
            </span>
          )}
          <button type="button" className="va-map-button" aria-label={t('map.showTrip')} title={t('map.showTrip')} onClick={() => focusOn('trip')}>
            <NodeIndexOutlined />
          </button>
          <button
            type="button"
            className={`va-map-button ${position.coordinates ? '' : 'va-map-button-inactive'}`}
            aria-label={t('map.position.locate')}
            title={t('map.position.locate')}
            onClick={focusPosition}
          >
            <AimOutlined />
          </button>
          <Link to="stops/neu" className="va-fab va-fab-dock" aria-label={t('trip.addStop')}>
            <PlusOutlined />
          </Link>
        </div>
        <section aria-label={t('trip.sheet')} className={`va-trip-sheet ${expanded ? 'va-trip-sheet-expanded' : ''}`}>
          <button
            type="button"
            className="va-trip-sheet-toggle"
            aria-expanded={expanded}
            onClick={() => setExpanded((value) => !value)}
          >
            <span className="va-sheet-grip" aria-hidden="true" />
            <span className="va-row va-space-between va-baseline va-full">
              <span className="va-sheet-summary">
                {t('common.stops', { count: stopCount })} · {t('common.hints', { count: incidents.length })}
              </span>
              <span className="va-muted">{expanded ? t('trip.showMap') : t('trip.showList')}</span>
            </span>
          </button>

          {deleteError && <Alert type="error" showIcon closable message={deleteError} onClose={() => setDeleteError(null)} />}

          {stopCount === 0 && <p className="va-empty">{t('trip.noStops')}</p>}

          <TodoList tripId={trip.id} version={data} onChanged={reload} compact={!expanded} onExpand={() => setExpanded(true)} />



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
                          {stopDays(stop.arrivalAt, stop.departureAt) ?? t('trip.noTimes')}
                          {stop.latitude == null && ` · ${t('trip.notOnMap')}`}
                        </span>
                      </span>
                      <Link to={`stops/${stop.id}/bearbeiten`} className="va-icon-button va-plain" aria-label={t('trip.editStopLabel', { name: stop.name })}>
                        <EditOutlined />
                      </Link>
                      {(bookingsByStop.get(stop.id) ?? []).some(isAppBooked) ? (
                        <button type="button" className="va-icon-button va-plain" aria-label={t('trip.deleteStopLabel', { name: stop.name })}
                          onClick={() => setStopToDelete(stop.id)}>
                          <DeleteOutlined />
                        </button>
                      ) : (
                        <Popconfirm
                          title={t('trip.deleteStop', { name: stop.name })}
                          description={stopDeletionText(trip, stop.id, bookingsByStop.get(stop.id) ?? [])}
                          okText={t('common.delete')}
                          okButtonProps={{ danger: true }}
                          cancelText={t('common.cancel')}
                          placement="topRight"
                          onConfirm={() => deleteStop(stop.id)}
                        >
                          <button type="button" className="va-icon-button va-plain" aria-label={t('trip.deleteStopLabel', { name: stop.name })}>
                            <DeleteOutlined />
                          </button>
                        </Popconfirm>
                      )}
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

      {stopToDelete !== null && stopById.get(stopToDelete) && (
        <RemoveAppBookingDialog
          bookings={(bookingsByStop.get(stopToDelete) ?? []).filter(isAppBooked)}
          stopName={stopById.get(stopToDelete)?.name ?? ''}
          // only what happens to the routes, the bookings are listed in the dialog
          detail={stopDeletionText(trip, stopToDelete, [])}
          remove={() => deleteStop(stopToDelete)}
          onClose={() => setStopToDelete(null)}
        />
      )}
      <Outlet context={context} />
    </div>
  );
}

/** Bookings below a stop in the list, each opens its dialog; "+ Buchung" adds one to the stop. */
function StopBookings({ bookings, stopId }: { bookings: Booking[]; stopId: number }) {
  return (
    <div className="va-stop-bookings">
      {bookings.map((booking) => (
        <Link key={booking.id} to={`buchungen/${booking.id}`} className="va-booking-row" aria-label={t('trip.viewBooking', { title: booking.title })}>
          <span className="va-booking-icon" aria-hidden="true"><HomeOutlined /></span>
          <span className="va-column va-grow">
            <span className="va-strong">{booking.title}</span>
            <span className="va-small va-muted">{stayLabel(booking)}{booking.provider && ` · ${booking.provider}`}</span>
          </span>
        </Link>
      ))}
      <Link to={`stops/${stopId}/buchung`} className="va-text-button va-add-booking">
        {t('trip.addBooking')}
      </Link>
    </div>
  );
}

function RouteRow({ context, routeId }: { context: TripContext; routeId: number }) {
  const route = context.trip.routes.find((candidate) => candidate.id === routeId)!;
  const incident = context.incidentsByRoute.get(route.id)?.[0];
  const line = context.routeLines.get(route.id);
  // with an incident the row leads to its solutions, like the chip on the map; the route itself is under "plan myself"
  const name = routeName(route, context.stopById);
  return (
    <Link
      to={incident ? `alternativen/${route.id}/${incident.incidentId}` : `routen/${route.id}`}
      className="va-route-row"
      aria-label={incident ? t('trip.viewSolutions', { route: name }) : t('trip.viewRoute', { route: name })}
    >
      <span className="va-column va-grow">
        <span className="va-strong">{name}</span>
        <span className="va-small va-muted">
          {[
            line?.via && t('trip.via', { via: line.via }),
            line && formatDuration(line.durationSeconds),
          ]
            .filter(Boolean)
            .join(' · ')}
        </span>
      </span>
      {incident && (
        <span className="va-chip va-chip-soft va-route-impact" title={incidentTypeLabel(incident.type)}>
          {/* same icon per kind of incident as on the map, static markup from incidentIcon */}
          <span className="va-route-impact-icon" role="img" aria-label={incidentTypeLabel(incident.type)}
            dangerouslySetInnerHTML={{ __html: incidentIcon(incident.type) }} />
          {impactLabel(incident.impact)}
        </span>
      )}
    </Link>
  );
}

function incidentKey(incident: { routeId: number; incidentId: number }): string {
  return `${incident.routeId}/${incident.incidentId}`;
}
