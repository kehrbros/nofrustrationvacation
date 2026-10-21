import { Route, Routes } from 'react-router';

import AuthCallback from './auth/AuthCallback';
import { CALLBACK_PATH } from './auth/paths';
import RequireAuth from './auth/RequireAuth';
import DashboardLayout from './layout/DashboardLayout';
import DashboardHome from './pages/DashboardHome';

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
        <Route index element={<DashboardHome />} />
      </Route>
    </Routes>
  );
}
