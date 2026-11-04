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
  configuredAuthority === 'same-origin' ? window.location.origin : (configuredAuthority ?? 'http://localhost:8080');

export const userManager = new UserManager({
  authority: oidcAuthority,
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID ?? 'nfv-frontend',
  redirect_uri: window.location.origin + CALLBACK_PATH,
  post_logout_redirect_uri: window.location.origin + '/',
  scope: 'openid profile email',
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  // public clients get no refresh token, an expired session restarts the login
  automaticSilentRenew: false,
});
