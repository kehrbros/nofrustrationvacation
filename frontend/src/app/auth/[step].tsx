import { Redirect } from 'expo-router';

/**
 * Target of the redirects vacantagent://auth/callback and vacantagent://auth/logout. The browser session of
 * expo-web-browser receives them, on Android the deep link also opens this route, which only leads back to the start.
 */
export default function AuthRedirect() {
  return <Redirect href="/" />;
}
