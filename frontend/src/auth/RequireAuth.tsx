import { Spin } from 'antd';
import { useEffect, type ReactNode } from 'react';
import { useAuth } from 'react-oidc-context';

import { t } from '../i18n';
import SessionExpiredDialog from './SessionExpiredDialog';
import { loginState, renewSession } from './session';

/**
 * Renders its children only for logged in users, all others are sent to the login page of the IdP and come back to
 * the same page. An expired access token does not unmount the page: it is renewed in the background (see session.ts),
 * if that is no longer possible a dialog asks for a new login over the page.
 */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const loggedIn = auth.user != null;
  const redirectToLogin = !auth.isLoading && !loggedIn && !auth.activeNavigator && !auth.error;
  // e.g. the app was reopened after a while, the stored refresh token is still valid
  const stale = loggedIn && auth.user?.expired === true;

  useEffect(() => {
    if (redirectToLogin) {
      void auth.signinRedirect({ state: loginState() });
    }
  }, [redirectToLogin, auth]);

  useEffect(() => {
    if (stale) {
      void renewSession();
    }
  }, [stale]);

  if (loggedIn) {
    return (
      <>
        {children}
        <SessionExpiredDialog />
      </>
    );
  }
  return <Spin fullscreen tip={t('auth.redirecting')} />;
}
