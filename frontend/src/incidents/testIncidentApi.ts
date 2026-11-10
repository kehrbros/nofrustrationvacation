import { client } from '../generated/api/client.gen';
import type { LinePoint } from '../trips/model';

/**
 * Test tool: report a traffic event by hand (`POST /api/v1/test/traffic-events`, source `manual`), see
 * Docs/traffic-feed.md#testquelle-manual. Only with `nfv.input.sources.manual.enabled=true` and the role `tester`;
 * the endpoint is not in openapi.json, so it is called through the configured client without generated types.
 */

export type IncidentType = 'CLOSURE' | 'CONGESTION' | 'ROADWORKS' | 'ACCIDENT' | 'OTHER';
export const INCIDENT_TYPES: IncidentType[] = ['CLOSURE', 'CONGESTION', 'ROADWORKS', 'ACCIDENT', 'OTHER'];

export type TravelDirection = 'NORTHBOUND' | 'EASTBOUND' | 'SOUTHBOUND' | 'WESTBOUND';
export const DIRECTIONS: TravelDirection[] = ['NORTHBOUND', 'EASTBOUND', 'SOUTHBOUND', 'WESTBOUND'];

export interface TestIncident {
  type: IncidentType;
  point: LinePoint;
  roadRef: string | null;
  /** null: both directions */
  direction: TravelDirection | null;
  /** ISO with offset */
  validFrom: string;
  validTo: string | null;
  delayMinutes: number | null;
  text: string | null;
}

/** What became of the event, as `TrafficEventStatusDto`, only the parts the wizard shows. */
export interface TestIncidentResult {
  /** INCIDENT, PENDING or the reason without incident, e.g. NO_SEGMENTS */
  outcome: string;
  note: string | null;
  incident: {
    id: number;
    segments: number;
    roads: { ref: string | null; name: string | null }[];
    consequences: { routeId: number }[];
  } | null;
}

export class TestIncidentError extends Error {
  constructor(readonly status: number | undefined, readonly detail: string | null) {
    super(detail ?? `test incident failed (${status ?? 'network'})`);
  }
}

/** Id of a new event; reporting again with the same id changes the event. */
export function newTestIncidentId(): string {
  return `app-${Date.now()}`;
}

/** Reports the event; the same id again changes it, resolved ends it. */
export async function reportTestIncident(id: string, incident: TestIncident, resolved = false): Promise<TestIncidentResult> {
  const { data, error, response } = await client.post<TestIncidentResult, { detail?: string }>({
    url: '/api/v1/test/traffic-events',
    body: {
      id,
      resolved,
      type: incident.type,
      point: { lat: incident.point.latitude, lon: incident.point.longitude },
      roadRef: incident.roadRef,
      direction: incident.direction,
      validFrom: incident.validFrom,
      validTo: incident.validTo,
      delayMinutes: incident.delayMinutes,
      text: incident.text,
    },
    headers: { 'Content-Type': 'application/json' },
  });
  if (error !== undefined || !response?.ok || !data) {
    const problem = error as { detail?: unknown } | undefined;
    const detail = problem && typeof problem.detail === 'string' ? problem.detail : null;
    throw new TestIncidentError(response?.status, detail);
  }
  return data;
}
