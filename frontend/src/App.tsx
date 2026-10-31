import { Navigate, Route, Routes } from 'react-router';

import AuthCallback from './auth/AuthCallback';
import { CALLBACK_PATH } from './auth/paths';
import RequireAuth from './auth/RequireAuth';
import PlaceholderPage from './components/PlaceholderPage';
import DashboardLayout from './layout/DashboardLayout';
import { HOME_PATH } from './layout/navigation';

export default function App() {
  return (
    <Routes>
      <Route path={CALLBACK_PATH} element={<AuthCallback />} />
      <Route
        element={
          <RequireAuth>
            <DashboardLayout />
          </RequireAuth>
        }
      >
        <Route
          path={HOME_PATH.slice(1)}
          element={
            <PlaceholderPage
              title="Meine Reisen"
              description="Hier erscheinen deine Routen und Ereignisse, die sie betreffen."
            />
          }
        />
        <Route path="*" element={<Navigate to={HOME_PATH} replace />} />
      </Route>
    </Routes>
  );
}
