/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_OIDC_AUTHORITY?: string;
  readonly VITE_OIDC_CLIENT_ID?: string;
  /** MapLibre style, default OpenFreeMap Positron. */
  readonly VITE_MAP_STYLE_URL?: string;
  /** Photon compatible place search, default the public instance of komoot. */
  readonly VITE_GEOCODING_URL?: string;
}
