import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

import { CALLBACK_PATH } from './paths';

/**
 * OIDC client of the frontend. The issuer is the own IdP of the backend (com.vacantagent.auth) or e.g.
 * Keycloak, see Docs/authentication.md.
 */
export const userManager = new UserManager({
  authority: import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8080',
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID ?? 'vacantagent-frontend',
  redirect_uri: window.location.origin + CALLBACK_PATH,
  post_logout_redirect_uri: window.location.origin + '/',
  scope: 'openid profile email',
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  // public clients get no refresh token, an expired session restarts the login
  automaticSilentRenew: false,
});
