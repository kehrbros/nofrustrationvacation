import {
  AuthRequest,
  exchangeCodeAsync,
  refreshAsync,
  resolveDiscoveryAsync,
  type DiscoveryDocument,
  type TokenResponse,
} from 'expo-auth-session';

import { config } from '../config';

/** Scheme of the app (app.json), the redirect URIs are registered for the client vacantagent-app in the backend. */
const SCHEME = 'vacantagent';
export const REDIRECT_URI = `${SCHEME}://auth/callback`;
export const POST_LOGOUT_REDIRECT_URI = `${SCHEME}://auth/logout`;
const SCOPES = ['openid', 'profile', 'email'];

let discovery: Promise<DiscoveryDocument> | undefined;

/** Endpoints of the IdP from its discovery document, loaded once. A failed load is retried on the next call. */
export function getDiscovery(): Promise<DiscoveryDocument> {
  discovery ??= resolveDiscoveryAsync(config.oidcIssuer).catch((error: unknown) => {
    discovery = undefined;
    throw error;
  });
  return discovery;
}

export type LoginResult = { type: 'success'; tokens: TokenResponse } | { type: 'cancel' } | { type: 'error'; message: string };

/**
 * Authorization Code flow with PKCE in the system browser (RFC 8252). The login page is the one of the IdP, the app
 * never sees the password.
 */
export async function login(): Promise<LoginResult> {
  const endpoints = await getDiscovery();
  const request = new AuthRequest({ clientId: config.oidcClientId, redirectUri: REDIRECT_URI, scopes: SCOPES, usePKCE: true });
  const result = await request.promptAsync(endpoints);
  if (result.type === 'error') {
    return { type: 'error', message: result.error?.description ?? result.error?.message ?? 'Anmeldung fehlgeschlagen' };
  }
  if (result.type !== 'success') {
    return { type: 'cancel' };
  }
  const tokens = await exchangeCodeAsync(
    {
      clientId: config.oidcClientId,
      code: result.params.code,
      redirectUri: REDIRECT_URI,
      extraParams: { code_verifier: request.codeVerifier ?? '' },
    },
    endpoints,
  );
  return { type: 'success', tokens };
}

export async function refresh(refreshToken: string): Promise<TokenResponse> {
  return refreshAsync({ clientId: config.oidcClientId, refreshToken }, await getDiscovery());
}

/** URL of the IdP logout, ends the login session in the browser so the next login asks for the password again. */
export async function logoutUrl(idToken: string): Promise<string | null> {
  const { endSessionEndpoint } = await getDiscovery();
  if (!endSessionEndpoint) {
    return null;
  }
  const params = new URLSearchParams({
    id_token_hint: idToken,
    client_id: config.oidcClientId,
    post_logout_redirect_uri: POST_LOGOUT_REDIRECT_URI,
  });
  return `${endSessionEndpoint}?${params.toString()}`;
}
