import type { IncidentType } from './model';

/*
 * Icons per kind of incident, shared by the map chips and the route rows so both look the same.
 * Plain SVG strings, because the map builds its chips as DOM elements. White filling with an ink outline;
 * the first path is the main shape, the "too late" chip on the map fills it in the accent colour.
 */
const svg = (body: string) =>
  '<svg width="18" height="18" viewBox="0 0 24 24" fill="#ffffff" stroke="#111111" stroke-width="2.2" ' +
  `stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;

/** Warning triangle: general alert and traffic jam. */
const WARNING = svg(
  '<path d="M12 3 L22 20 H2 Z"/><path d="M12 10 V14" fill="none"/><circle cx="12" cy="17" r="0.8" fill="#111111"/>',
);

/** Car with an impact mark: accident. */
const ACCIDENT = svg(
  '<path d="M1.5 18 V14.5 L4 10 H11.5 L14.5 14 H16.5 Q18 14 18 15.5 V18 Z"/>' +
  '<circle cx="5.5" cy="18.5" r="1.8"/><circle cx="14" cy="18.5" r="1.8"/>' +
  '<path d="M20 11.5 L22 9.5 M20.5 15 H23 M18 9.5 L18.5 7" fill="none"/>',
);

/** Barrier board with stripes on two legs: roadworks. */
const BARRIER = svg(
  '<path d="M2.5 7 H21.5 V12.5 H2.5 Z"/><path d="M7.5 7 L4.5 12.5 M13 7 L10 12.5 M18.5 7 L15.5 12.5" fill="none"/>' +
  '<path d="M6 12.5 V20 M18 12.5 V20" fill="none"/><path d="M4 20 H8 M16 20 H20" fill="none"/>',
);

/** Round sign with a bar, like "no entry": road closed. */
const CLOSED = svg(
  '<path d="M12 3 A9 9 0 1 1 11.99 3 Z"/><path d="M7 10.6 H17 V13.4 H7 Z" fill="#111111" stroke-width="0"/>',
);

const ICONS: Record<IncidentType, string> = {
  OTHER: WARNING,
  CONGESTION: WARNING,
  ACCIDENT,
  ROADWORKS: BARRIER,
  CLOSURE: CLOSED,
};

/** SVG markup of the icon for the kind of incident, a warning triangle for unknown kinds. */
export function incidentIcon(type: IncidentType | null | undefined): string {
  return (type && ICONS[type]) || WARNING;
}
