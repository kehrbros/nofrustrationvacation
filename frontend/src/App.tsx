import { Button } from 'antd';
import { Navigate, Route, Routes, useNavigate } from 'react-router';

import AuthCallback from './auth/AuthCallback';
import { CALLBACK_PATH } from './auth/paths';
import RequireAuth from './auth/RequireAuth';
import PlaceholderPage from './components/PlaceholderPage';
import DashboardLayout from './layout/DashboardLayout';
import ConsumerHome from './pages/consumer/ConsumerHome';
import TopUpPage from './pages/consumer/TopUpPage';
import TopUpReturnPage from './pages/consumer/TopUpReturnPage';
import OnboardingPage from './pages/OnboardingPage';
import PayPalConnectedPage from './pages/paypal/PayPalConnectedPage';
import RequireProviderOnboarding from './onboarding/RequireProviderOnboarding';
import EarningsPage from './pages/provider/EarningsPage';
import PayoutAccountPage from './pages/provider/PayoutAccountPage';
import ProviderHome from './pages/provider/ProviderHome';
import {
  ONBOARDING_PATH,
  PAYOUT_ACCOUNT_PATH,
  PAYPAL_CONNECTED_PATH,
  roleHome,
  TOP_UP_RETURN_PATH,
} from './role/navigation';
import { useRole } from './role/RoleContext';

/** Sends "/" to the start page of the selected role, or to the onboarding if no role was picked yet. */
function RoleRedirect() {
  const { role } = useRole();
  return <Navigate to={role ? roleHome[role] : ONBOARDING_PATH} replace />;
}

function NavigateButton({ to, label }: { to: string; label: string }) {
  const navigate = useNavigate();
  return (
    <Button className="va-accent" size="large" onClick={() => navigate(to)}>
      {label}
    </Button>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path={CALLBACK_PATH} element={<AuthCallback />} />
      <Route
        path={ONBOARDING_PATH}
        element={
          <RequireAuth>
            <OnboardingPage />
          </RequireAuth>
        }
      />
      <Route
        path={PAYPAL_CONNECTED_PATH}
        element={
          <RequireAuth>
            <PayPalConnectedPage />
          </RequireAuth>
        }
      />
      <Route
        element={
          <RequireAuth>
            <DashboardLayout />
          </RequireAuth>
        }
      >
        <Route index element={<RoleRedirect />} />

        <Route path="consumer" element={<ConsumerHome />} />
        <Route
          path="consumer/auftraege"
          element={
            <PlaceholderPage
              title="Meine Aufträge"
              extra={<NavigateButton to="/consumer/auftraege/neu" label="Tokens kaufen" />}
            />
          }
        />
        <Route
          path="consumer/auftraege/neu"
          element={
            <PlaceholderPage
              title="Tokens kaufen"
              description="Sag uns, was du höchstens zahlst. Wir suchen das günstigste passende Angebot."
            />
          }
        />
        <Route path="consumer/guthaben" element={<TopUpPage />} />
        <Route path={TOP_UP_RETURN_PATH.slice(1)} element={<TopUpReturnPage />} />
        <Route path="consumer/verbrauch" element={<PlaceholderPage title="Verbrauch" />} />

        <Route path="provider" element={<ProviderHome />} />
        <Route
          path="provider/angebote"
          element={
            <PlaceholderPage
              title="Meine Angebote"
              extra={<NavigateButton to="/provider/angebote/neu" label="Neues Angebot" />}
            />
          }
        />
        <Route
          path="provider/angebote/neu"
          element={
            <RequireProviderOnboarding title="Kapazität anbieten">
              <PlaceholderPage
                title="Kapazität anbieten"
                description="Du legst Preis und Menge fest. Passende Aufträge werden automatisch zugeordnet."
              />
            </RequireProviderOnboarding>
          }
        />
        <Route path="provider/einnahmen" element={<EarningsPage />} />
        <Route path={PAYOUT_ACCOUNT_PATH.slice(1)} element={<PayoutAccountPage />} />

        <Route
          path="marktplatz"
          element={
            <PlaceholderPage title="Marktplatz" description="Aktuelle Preise pro 1 Mio. Tokens, für alle sichtbar." />
          }
        />

        <Route path="betreiber" element={<PlaceholderPage title="Orderbuch" />} />
        <Route path="betreiber/vereinbarungen" element={<PlaceholderPage title="Vereinbarungen" />} />
        <Route path="betreiber/nutzer" element={<PlaceholderPage title="Nutzer" />} />

        <Route path="*" element={<RoleRedirect />} />
      </Route>
    </Routes>
  );
}
