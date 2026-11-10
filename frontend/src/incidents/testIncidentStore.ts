import type { TestIncident } from './testIncidentApi';

/**
 * Test incidents reported from this device, so they can be changed or ended (same id again), see
 * Docs/traffic-feed.md#testquelle-manual. Only in this browser; the test source keeps its events in memory, after a
 * restart of the backend the same id creates the event anew.
 */
export interface StoredTestIncident {
  id: string;
  input: TestIncident;
  /** ISO */
  reportedAt: string;
  /** INCIDENT or the reason without incident, of the last report */
  outcome: string;
  resolved: boolean;
}

const KEY = 'nfv.testIncidents';
const MAX = 20;

export function storedTestIncidents(): StoredTestIncident[] {
  try {
    const value = JSON.parse(localStorage.getItem(KEY) ?? '[]') as unknown;
    return Array.isArray(value)
      ? value.filter((entry): entry is StoredTestIncident => typeof entry?.id === 'string' && typeof entry?.input === 'object')
      : [];
  } catch {
    return [];
  }
}

/** Adds or replaces the entry with its id, newest first, at most 20. */
export function storeTestIncident(entry: StoredTestIncident): StoredTestIncident[] {
  const entries = [entry, ...storedTestIncidents().filter((other) => other.id !== entry.id)].slice(0, MAX);
  try {
    localStorage.setItem(KEY, JSON.stringify(entries));
  } catch {
    // private mode: the list is lost, the incident itself is reported
  }
  return entries;
}

export function forgetTestIncident(id: string): StoredTestIncident[] {
  const entries = storedTestIncidents().filter((entry) => entry.id !== id);
  try {
    localStorage.setItem(KEY, JSON.stringify(entries));
  } catch {
    // see above
  }
  return entries;
}
