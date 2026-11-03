/**
 * Place search for new stops via Photon (OpenStreetMap data, https://photon.komoot.io), called from the browser.
 * The public instance is meant for moderate use only; for production a backend proxy or own instance is open,
 * see Docs/architecture.md.
 */

export interface Place {
  name: string;
  /** Region and country, e.g. "Toskana, Italien". */
  detail: string;
  latitude: number;
  longitude: number;
  /** OpenStreetMap object, e.g. osm:node/123. */
  osmRef?: string;
  /** Street, house number, postcode and town, e.g. "Via Roma 1, 00184 Roma"; empty if unknown. */
  addressLine?: string;
  /** ISO 3166-1 alpha-2, upper case. */
  countryCode?: string;
}

export interface SearchOptions {
  /** Prefer results near this point. */
  near?: { latitude: number; longitude: number } | null;
  /** Only objects with this OSM key (or key:value), e.g. tourism. */
  osmTag?: string;
}

const PHOTON_URL = import.meta.env.VITE_GEOCODING_URL ?? 'https://photon.komoot.io/api/';

interface PhotonFeature {
  geometry: { coordinates: [number, number] };
  properties: {
    name?: string;
    street?: string;
    housenumber?: string;
    city?: string;
    postcode?: string;
    state?: string;
    countrycode?: string;
    country?: string;
    type?: string;
    osm_type?: string;
    osm_id?: number;
  };
}

const OSM_TYPES: Record<string, string> = { N: 'node', W: 'way', R: 'relation' };

export async function searchPlaces(query: string, signal?: AbortSignal, options: SearchOptions = {}): Promise<Place[]> {
  const url = new URL(PHOTON_URL, window.location.href);
  url.searchParams.set('q', query);
  url.searchParams.set('limit', '5');
  url.searchParams.set('lang', 'de');
  if (options.near) {
    url.searchParams.set('lat', options.near.latitude.toFixed(5));
    url.searchParams.set('lon', options.near.longitude.toFixed(5));
  }
  if (options.osmTag) {
    url.searchParams.set('osm_tag', options.osmTag);
  }
  const response = await fetch(url, { signal });
  if (!response.ok) {
    throw new Error(`place search failed (${response.status})`);
  }
  const body = (await response.json()) as { features?: PhotonFeature[] };
  return (body.features ?? [])
    .filter((feature) => feature.properties.name)
    .map((feature) => {
      const { name, street, housenumber, city, postcode, state, country, countrycode, osm_type: osmType, osm_id: osmId } =
        feature.properties;
      const address = street ? [street, housenumber].filter(Boolean).join(' ') : undefined;
      const town = [postcode, city].filter(Boolean).join(' ');
      const detail = [address, city !== name ? city : undefined, state, country].filter(Boolean).join(', ');
      return {
        name: name!,
        detail,
        longitude: feature.geometry.coordinates[0],
        latitude: feature.geometry.coordinates[1],
        osmRef: osmType && osmId ? `osm:${OSM_TYPES[osmType] ?? osmType}/${osmId}` : undefined,
        addressLine: [address, town].filter(Boolean).join(', '),
        countryCode: countrycode && /^[a-z]{2}$/i.test(countrycode) ? countrycode.toUpperCase() : undefined,
      };
    });
}
