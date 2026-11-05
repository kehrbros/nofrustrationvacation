import type { User, UserManager } from 'oidc-client-ts';

/**
 * Keeps the login of the running app alive: the access token is renewed with the refresh token (rotated on every use)
 * before it expires and before API requests. Only if the IdP rejects the refresh token is the session expired, the app
 * then asks for a new login without leaving the current page (see SessionExpiredDialog), so entered data is kept.
 */

/** Requests start a renewal if the access token is valid for less than this. */
const MIN_VALIDITY_SECONDS = 30;

let manager: UserManager | null = null;
let renewal: Promise<User | null> | null = null;
/** Number of rejected renewals since the last login, 0: the session is valid. */
let rejections = 0;
const listeners = new Set<() => void>();

export function configureSession(userManager: UserManager) {
  manager = userManager;
  userManager.events.addAccessTokenExpired(() => void renewSession());
  userManager.events.addSilentRenewError((error) => {
    if (isRejected(error)) {
      reject();
    }
  });
  userManager.events.addUserLoaded(() => {
    rejections = 0;
    notify();
  });
}

/** Access token for an API request, renewed first if it is (about to be) expired. */
export async function accessToken(): Promise<string | undefined> {
  if (!manager) {
    return undefined;
  }
  let user = await manager.getUser();
  if (user && rejections === 0 && (user.expires_in ?? Infinity) < MIN_VALIDITY_SECONDS) {
    user = await renewSession();
  }
  return user?.access_token;
}

/**
 * Renews the access token with the refresh token, concurrent calls share one renewal. Resolves to the renewed user,
 * or the current one if renewing failed but its token is still valid, otherwise null.
 */
export function renewSession(): Promise<User | null> {
  if (!manager) {
    return Promise.resolve(null);
  }
  renewal ??= renew(manager).finally(() => {
    renewal = null;
  });
  return renewal;
}

async function renew(userManager: UserManager): Promise<User | null> {
  const user = await userManager.getUser();
  if (!user) {
    return null;
  }
  if (!user.refresh_token) {
    reject();
    return user.expired ? null : user;
  }
  try {
    return await userManager.signinSilent();
  } catch (error) {
    // network errors (offline) do not end the session, the next request tries again
    if (isRejected(error)) {
      reject();
    }
    return user.expired ? null : user;
  }
}

/** The IdP answered with an OAuth error (e.g. invalid_grant): the refresh token is expired or revoked. */
function isRejected(error: unknown) {
  return error instanceof Error && error.name === 'ErrorResponse';
}

/** 0 while the session is valid, otherwise counts the rejections, so a closed dialog opens again on the next one. */
export function sessionRejections() {
  return rejections;
}

export function subscribeSession(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function reject() {
  rejections++;
  notify();
}

function notify() {
  listeners.forEach((listener) => listener());
}

/** Path to return to after a login with redirect: the page the user was on. */
export interface LoginState {
  returnTo: string;
}

export function loginState(): LoginState {
  return { returnTo: window.location.pathname + window.location.search + window.location.hash };
}

/** Only paths of the own app, no open redirect to other sites. */
export function returnPath(state: unknown): string {
  const returnTo = (state as Partial<LoginState> | null | undefined)?.returnTo;
  return typeof returnTo === 'string' && returnTo.startsWith('/') && !returnTo.startsWith('//') ? returnTo : '/';
}
