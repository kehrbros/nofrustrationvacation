import { Navigate, Route, Routes } from 'react-router';

import AuthCallback from './auth/AuthCallback';
import AlternativesSheet from './bookings/AlternativesSheet';
import BookingSheet from './bookings/BookingSheet';
import ShareTargetPage from './bookings/ShareTargetPage';
import StayBookingSheet from './bookings/StayBookingSheet';
import PlanBookingSheet from './proposals/PlanBookingSheet';
import StopBookingSheet from './bookings/StopBookingSheet';

import { CALLBACK_PATH } from './auth/paths';
import RequireAuth from './auth/RequireAuth';
import { SHARE_PATH } from './bookings/shareTarget';
import { HOME_PATH } from './layout/navigation';
import IncidentSheet from './pages/IncidentSheet';
import NewTripPage from './pages/NewTripPage';
import RouteSheet from './pages/RouteSheet';
import TestIncidentSheet from './incidents/TestIncidentSheet';
import StopSheet from './pages/StopSheet';
import TripAlternativesSheet from './pages/TripAlternativesSheet';
import TripPage from './pages/TripPage';
import TripsPage from './pages/TripsPage';

/**
 * Pages of the travel app, see the UI concept in Docs/architecture.md. Dialogs over the map (incident, route, new
 * stop, bookings) are child routes of the trip, so they have their own URL and the back button closes them.
 */
export default function App() {
  return (
    <Routes>
      <Route path={CALLBACK_PATH} element={<AuthCallback />} />
      <Route
        path={HOME_PATH}
        element={
          <RequireAuth>
            <TripsPage />
          </RequireAuth>
        }
      />
      <Route
        path={`${HOME_PATH}/neu`}
        element={
          <RequireAuth>
            <NewTripPage />
          </RequireAuth>
        }
      />
      <Route
        path={`${HOME_PATH}/:tripId`}
        element={
          <RequireAuth>
            <TripPage />
          </RequireAuth>
        }
      >
        <Route path="stops/neu" element={<StopSheet />} />
        <Route path="stops/:stopId/bearbeiten" element={<StopSheet />} />
        <Route path="stops/:stopId/buchung" element={<StopBookingSheet />} />
        <Route path="stops/:stopId/unterkunft" element={<StayBookingSheet />} />
        <Route path="plan" element={<PlanBookingSheet />} />
        <Route path="plan/:executionId" element={<PlanBookingSheet />} />

        <Route path="buchungen/:bookingId" element={<BookingSheet />} />
        <Route path="buchungen/:bookingId/alternativen" element={<AlternativesSheet />} />
        <Route path="hinweise/:routeId/:incidentId" element={<IncidentSheet />} />
        <Route path="alternativen/:routeId/:incidentId" element={<TripAlternativesSheet />} />
        <Route path="routen/:routeId" element={<RouteSheet />} />
        <Route path="stoerungen/neu" element={<TestIncidentSheet />} />
      </Route>
      <Route
        path={SHARE_PATH}
        element={
          <RequireAuth>
            <ShareTargetPage />
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to={HOME_PATH} replace />} />
    </Routes>
  );
}
