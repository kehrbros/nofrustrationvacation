import type { Role } from './RoleContext';

export interface NavigationItem {
  path: string;
  label: string;
}

/** Page where a new user picks the primary role. */
export const ONBOARDING_PATH = '/willkommen';

/** Redirect URI of "Log in with PayPal", registered in the PayPal app (vacantagent.paypal.connect-redirect-uri). */
export const PAYPAL_CONNECTED_PATH = '/paypal/connected';

/** Config pages behind the items of the provider onboarding checklist. */
export const PAYOUT_ACCOUNT_PATH = '/provider/auszahlungskonto';
export const AGENTS_PATH = '/provider/agents';

/** Return URL of PayPal payments (vacantagent.paypal.checkout-return-url and -cancel-url). */
export const TOP_UP_RETURN_PATH = '/consumer/guthaben/paypal';

/** Start page of each role. */
export const roleHome: Record<Role, string> = {
  consumer: '/consumer',
  provider: '/provider',
  operator: '/betreiber',
};

export const roleLabel: Record<Role, string> = {
  consumer: 'Consumer',
  provider: 'Provider',
  operator: 'Betreiber',
};

/** Top navigation of each role. */
export const navigation: Record<Role, NavigationItem[]> = {
  consumer: [
    { path: '/consumer', label: 'Übersicht' },
    { path: '/consumer/auftraege', label: 'Meine Aufträge' },
    { path: '/consumer/guthaben', label: 'Guthaben' },
    { path: '/consumer/verbrauch', label: 'Verbrauch' },
    { path: '/marktplatz', label: 'Marktplatz' },
  ],
  provider: [
    { path: '/provider', label: 'Übersicht' },
    { path: '/provider/angebote', label: 'Meine Angebote' },
    { path: '/provider/agents', label: 'Agents' },
    { path: '/provider/einnahmen', label: 'Einnahmen' },
    { path: '/marktplatz', label: 'Marktplatz' },
  ],
  operator: [
    { path: '/betreiber', label: 'Orderbuch' },
    { path: '/betreiber/vereinbarungen', label: 'Vereinbarungen' },
    { path: '/betreiber/nutzer', label: 'Nutzer' },
  ],
};

/** Navigation entry that belongs to the current path, the longest matching prefix wins. */
export function selectedNavigationPath(role: Role, pathname: string): string | undefined {
  return navigation[role]
    .map((item) => item.path)
    .filter((path) => pathname === path || pathname.startsWith(path + '/'))
    .sort((a, b) => b.length - a.length)[0];
}
