import { refresh } from './oidc';
import { Session } from './session';
import { secureTokenStorage } from './tokenStorage';

/** The session of the app, shared by the AuthProvider and the API client. */
export const session = new Session(secureTokenStorage, refresh);

export { AuthProvider, useAuth } from './AuthProvider';
