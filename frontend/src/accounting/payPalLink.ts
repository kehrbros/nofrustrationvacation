import { startPayPalLink } from '../generated/api';

const RETURN_TO_KEY = 'vacantagent.paypal.returnTo';

/**
 * Starts "Log in with PayPal": the browser leaves the app and comes back to /paypal/connected, which continues
 * at {@code returnTo}.
 *
 * @returns the error of the API if PayPal could not be started
 */
export async function connectPayPal(returnTo: string): Promise<unknown> {
  const { data, error } = await startPayPalLink();
  if (!data) {
    return error ?? {};
  }
  try {
    window.sessionStorage.setItem(RETURN_TO_KEY, returnTo);
  } catch {
    // continues on the provider start page
  }
  window.location.assign(data.authorizationUrl);
  return undefined;
}

/** Page to continue at after linking, read once. */
export function takeReturnTo(fallback: string): string {
  try {
    const returnTo = window.sessionStorage.getItem(RETURN_TO_KEY);
    window.sessionStorage.removeItem(RETURN_TO_KEY);
    return returnTo?.startsWith('/') && !returnTo.startsWith('//') ? returnTo : fallback;
  } catch {
    return fallback;
  }
}
