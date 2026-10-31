/**
 * Runtime configuration. Expo inlines EXPO_PUBLIC_* variables at build time (.env.local, EAS build profiles), see
 * .env.example.
 */
const backendUrl = process.env.EXPO_PUBLIC_BACKEND_URL ?? 'http://localhost:8080';

export const config = {
  /** Backend with the API (vacantagent.backend-url of the backend). */
  backendUrl,
  /** Issuer of the IdP, the own IdP of the backend or e.g. Keycloak. */
  oidcIssuer: process.env.EXPO_PUBLIC_OIDC_ISSUER ?? backendUrl,
  oidcClientId: process.env.EXPO_PUBLIC_OIDC_CLIENT_ID ?? 'vacantagent-app',
} as const;
