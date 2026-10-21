import { Button, Result, Spin } from 'antd';
import { useAuth } from 'react-oidc-context';
import { Navigate } from 'react-router';

/**
 * Redirect target of the IdP. The AuthProvider exchanges the code for tokens, afterwards the user lands on
 * the dashboard start page.
 */
export default function AuthCallback() {
  const auth = useAuth();

  if (auth.error) {
    return (
      <Result
        status="error"
        title="Anmeldung fehlgeschlagen"
        subTitle={auth.error.message}
        extra={<Button type="primary" onClick={() => void auth.signinRedirect()}>Erneut anmelden</Button>}
      />
    );
  }
  if (auth.isAuthenticated) {
    return <Navigate to="/" replace />;
  }
  return <Spin fullscreen tip="Anmeldung wird abgeschlossen …" />;
}
