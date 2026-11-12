import { client } from '../generated/api/client.gen';
import type { Language } from '../i18n';

/**
 * Demo trip for testers and the jury (`/api/v1/demo-trips`), see Docs/demo-trip.md. Only on servers with the manual
 * test source; the endpoint is not in openapi.json, so it is called through the configured client without generated
 * types.
 */

export interface DemoTripResult {
  tripId: number;
  incidents: { key: string; placedOnRoute: boolean; outcome: string | null }[];
}

/** Whether this server offers demo trips (204), false on 404 or any failure. */
export async function demoTripsAvailable(): Promise<boolean> {
  try {
    const { response } = await client.get({ url: '/api/v1/demo-trips' });
    return response?.status === 204;
  } catch {
    return false;
  }
}

/** Creates a new demo trip starting tomorrow, names and texts in the language. */
export async function createDemoTrip(language: Language): Promise<DemoTripResult> {
  const { data, error, response } = await client.post<DemoTripResult>({
    url: '/api/v1/demo-trips',
    query: { language },
  });
  if (error !== undefined || !response?.ok || !data) {
    throw new Error(`demo trip failed (${response?.status ?? 'network'})`);
  }
  return data;
}
