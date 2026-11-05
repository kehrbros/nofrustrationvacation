import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

import { CALLBACK_PATH } from './paths';

/**
 * OIDC client of the frontend. The issuer is the own IdP of the backend (com.nfv.auth) or e.g.
 * Keycloak, see Docs/authentication.md.
 */
const configuredAuthority = import.meta.env.VITE_OIDC_AUTHORITY;

/**
 * "same-origin" (images of the Swarm stack): frontend and IdP share one origin behind Caddy,
 * see Docs/deployment.md#tls-caddy.
 */
const oidcAuthority =
  configuredAuthority === 'same-origin' ? window.location.origin : (configuredAuthority ?? 'http://localhost:18180');

export const userManager = new UserManager({
  authority: oidcAuthority,
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID ?? 'nfv-frontend',
  redirect_uri: window.location.origin + CALLBACK_PATH,
  post_logout_redirect_uri: window.location.origin + '/',
  scope: 'openid profile email',
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  // renews the access token with the refresh token (rotated by the IdP) shortly before it expires, see session.ts
  automaticSilentRenew: true,
  // logout revokes the refresh token, the access token expires after 15 minutes
  revokeTokenTypes: ['refresh_token'],
});
