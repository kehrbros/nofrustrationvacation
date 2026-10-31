export interface NavigationItem {
  path: string;
  label: string;
}

/** Start page after the login. */
export const HOME_PATH = '/reisen';

/** Top navigation. */
export const navigation: NavigationItem[] = [{ path: HOME_PATH, label: 'Meine Reisen' }];

/** Navigation entry that belongs to the current path, the longest matching prefix wins. */
export function selectedNavigationPath(pathname: string): string | undefined {
  return navigation
    .map((item) => item.path)
    .filter((path) => pathname === path || pathname.startsWith(path + '/'))
    .sort((a, b) => b.length - a.length)[0];
}
