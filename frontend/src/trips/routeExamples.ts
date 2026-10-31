import type { Stop } from './model';

/** Way suggested for a route. Until a routing service is connected these are examples, see Docs/architecture.md. */
export interface RouteSuggestion {
  name: string;
  minutes: number;
  kilometers: number;
}

const EARTH_RADIUS_KM = 6371;

function distanceKm(from: Stop, to: Stop): number {
  const rad = (degrees: number) => (degrees * Math.PI) / 180;
  const dLat = rad(to.latitude! - from.latitude!);
  const dLon = rad(to.longitude! - from.longitude!);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(from.latitude!)) * Math.cos(rad(to.latitude!)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
}

/** Two example ways derived from the straight-line distance, empty without coordinates. */
export function exampleSuggestions(from: Stop | undefined, to: Stop | undefined): RouteSuggestion[] {
  if (from?.latitude == null || to?.latitude == null) {
    return [];
  }
  const direct = distanceKm(from, to);
  const fastest = Math.round(direct * 1.25);
  const other = Math.round(direct * 1.45);
  return [
    { name: 'Schnellste Strecke (Beispiel)', kilometers: fastest, minutes: Math.round((fastest / 90) * 60) },
    { name: 'Alternative (Beispiel)', kilometers: other, minutes: Math.round((other / 75) * 60) },
  ];
}

export function formatDuration(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  return hours === 0 ? `${minutes} min` : `${hours} h ${String(minutes % 60).padStart(2, '0')}`;
}
