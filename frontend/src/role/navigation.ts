import type { Role } from './RoleContext';

export interface NavigationItem {
  path: string;
  label: string;
}

/** Page where a new user picks the primary role. */
export const ONBOARDING_PATH = '/willkommen';

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
    { path: '/consumer/verbrauch', label: 'Verbrauch' },
    { path: '/marktplatz', label: 'Marktplatz' },
  ],
  provider: [
    { path: '/provider', label: 'Übersicht' },
    { path: '/provider/angebote', label: 'Meine Angebote' },
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
