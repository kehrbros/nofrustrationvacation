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
}

const PHOTON_URL = import.meta.env.VITE_GEOCODING_URL ?? 'https://photon.komoot.io/api/';

interface PhotonFeature {
  geometry: { coordinates: [number, number] };
  properties: { name?: string; city?: string; state?: string; country?: string; type?: string };
}

export async function searchPlaces(query: string, signal?: AbortSignal): Promise<Place[]> {
  const url = new URL(PHOTON_URL, window.location.href);
  url.searchParams.set('q', query);
  url.searchParams.set('limit', '5');
  url.searchParams.set('lang', 'de');
  const response = await fetch(url, { signal });
  if (!response.ok) {
    throw new Error(`place search failed (${response.status})`);
  }
  const body = (await response.json()) as { features?: PhotonFeature[] };
  return (body.features ?? [])
    .filter((feature) => feature.properties.name)
    .map((feature) => {
      const { name, city, state, country } = feature.properties;
      const detail = [city !== name ? city : undefined, state, country].filter(Boolean).join(', ');
      return {
        name: name!,
        detail,
        longitude: feature.geometry.coordinates[0],
        latitude: feature.geometry.coordinates[1],
      };
    });
}
