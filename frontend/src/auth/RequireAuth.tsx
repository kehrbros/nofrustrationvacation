import { Spin } from 'antd';
import { useEffect, type ReactNode } from 'react';
import { useAuth } from 'react-oidc-context';

/**
 * Renders its children only for authenticated users, all others are sent to the login page of the IdP.
 */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const redirectToLogin = !auth.isLoading && !auth.isAuthenticated && !auth.activeNavigator && !auth.error;

  useEffect(() => {
    if (redirectToLogin) {
      void auth.signinRedirect();
    }
  }, [redirectToLogin, auth]);

  // an expired access token restarts the login, which passes through while the IdP session is still valid
  useEffect(() => auth.events.addAccessTokenExpired(() => void auth.removeUser()), [auth]);

  if (auth.isAuthenticated) {
    return children;
  }
  return <Spin fullscreen tip="Weiterleitung zur Anmeldung …" />;
}
