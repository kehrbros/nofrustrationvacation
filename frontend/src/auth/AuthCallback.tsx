import { Button, Result, Spin } from 'antd';
import { useAuth } from 'react-oidc-context';
import { Navigate } from 'react-router';

import { loginState, returnPath } from './session';

/**
 * Redirect target of the IdP. The AuthProvider exchanges the code for tokens, afterwards the user returns to the page
 * the login started on. In the login popup (SessionExpiredDialog) the AuthProvider closes the window instead.
 */
export default function AuthCallback() {
  const auth = useAuth();

  if (auth.error) {
    return (
      <Result
        status="error"
        title="Anmeldung fehlgeschlagen"
        subTitle={auth.error.message}
        extra={<Button type="primary" onClick={() => void auth.signinRedirect({ state: loginState() })}>Erneut anmelden</Button>}
      />
    );
  }
  if (auth.isAuthenticated) {
    return <Navigate to={returnPath(auth.user?.state)} replace />;
  }
  return <Spin fullscreen tip="Anmeldung wird abgeschlossen …" />;
}
