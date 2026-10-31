import * as WebBrowser from 'expo-web-browser';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { login, logoutUrl, POST_LOGOUT_REDIRECT_URI } from './oidc';
import type { Session } from './session';

export type AuthStatus = 'loading' | 'signedOut' | 'signedIn';

interface AuthContextValue {
  status: AuthStatus;
  /** Message of the last failed login. */
  error: string | null;
  signIn(): Promise<void>;
  signOut(): Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/** Login state of the app. Restores the session of the last start, the routes are guarded in app/_layout.tsx. */
export function AuthProvider({ session, children }: { session: Session; children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // also ends in signedOut when the API client ends the session (failed refresh, 401)
    const unsubscribe = session.subscribe((tokens) => setStatus(tokens ? 'signedIn' : 'signedOut'));
    session.restore().catch(() => setStatus('signedOut'));
    return unsubscribe;
  }, [session]);

  const signIn = useCallback(async () => {
    setError(null);
    try {
      const result = await login();
      if (result.type === 'success') {
        await session.start(result.tokens);
      } else if (result.type === 'error') {
        setError(result.message);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Anmeldung fehlgeschlagen');
    }
  }, [session]);

  const signOut = useCallback(async () => {
    const idToken = session.current?.idToken;
    await session.end();
    if (idToken) {
      // best effort, the tokens in the app are gone anyway
      try {
        const url = await logoutUrl(idToken);
        if (url) {
          await WebBrowser.openAuthSessionAsync(url, POST_LOGOUT_REDIRECT_URI);
        }
      } catch {
        // IdP not reachable
      }
    }
  }, [session]);

  const value = useMemo(() => ({ status, error, signIn, signOut }), [status, error, signIn, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth requires an AuthProvider');
  }
  return context;
}
