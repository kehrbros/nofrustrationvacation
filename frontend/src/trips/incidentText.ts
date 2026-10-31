import type { Impact, IncidentType, TripIncident } from './model';

const typeLabels: Record<IncidentType, string> = {
  CLOSURE: 'Vollsperrung',
  CONGESTION: 'Stau',
  ROADWORKS: 'Baustelle',
  ACCIDENT: 'Unfall',
  OTHER: 'Hinweis',
};

export function incidentTypeLabel(type: IncidentType): string {
  return typeLabels[type];
}

const MAX_TITLE_LENGTH = 60;

/** Whether the description is short enough to serve as title, e.g. "Vollsperrung A22". */
export function descriptionIsTitle(incident: TripIncident): boolean {
  const description = incident.description?.trim();
  return !!description && description.length <= MAX_TITLE_LENGTH;
}

/** Short title for chips on the map and list entries: a short description, otherwise the kind of incident. */
export function incidentTitle(incident: TripIncident): string {
  return descriptionIsTitle(incident) ? incident.description!.trim() : typeLabels[incident.type];
}

export function impactLabel(impact: Impact): string {
  return impact === 'BLOCKED' ? 'Gesperrt' : 'Verzögerung';
}

/** "45 Min.", "1 Std. 30 Min.", or a fallback when unknown. */
export function formatDelay(incident: TripIncident): string {
  if (incident.delayMinutes == null) {
    return incident.impact === 'BLOCKED' ? 'keine Angabe (gesperrt)' : 'keine Angabe';
  }
  const hours = Math.floor(incident.delayMinutes / 60);
  const minutes = incident.delayMinutes % 60;
  if (hours === 0) {
    return `${minutes} Min.`;
  }
  return minutes === 0 ? `${hours} Std.` : `${hours} Std. ${minutes} Min.`;
}
