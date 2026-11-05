import type { Category, Impact, IncidentType, TripIncident } from './model';
import { formatTime } from './time';

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
  return formatMinutes(incident.delayMinutes);
}

/** "45 Min.", "2 Std.", "1 Std. 30 Min." */
export function formatMinutes(total: number): string {
  const hours = Math.floor(total / 60);
  const minutes = total % 60;
  if (hours === 0) {
    return `${minutes} Min.`;
  }
  return minutes === 0 ? `${hours} Std.` : `${hours} Std. ${minutes} Min.`;
}

const categoryLabels: Record<Category, string> = {
  TOO_LATE: 'Zu spät',
  DELAYED: 'Verzögerung',
  NO_IMPACT: 'Ohne Auswirkung',
};

/** Label of the effect on the arrival, e.g. for the chip in the alternatives dialog. */
export function categoryLabel(category: Category | null): string {
  return category ? categoryLabels[category] : 'Wird bewertet';
}

/** Not passable at all: assessed, but without own delay. */
export function isUnpassable(consequence: TripIncident): boolean {
  return consequence.category !== null && consequence.assessedDelayMinutes === null;
}

/**
 * What the consequence means for the trip, as headline and detail line, e.g. "Du kommst zu spät an" and
 * "Ankunft in Verona ca. 19:40 statt 18:00".
 */
export function consequenceText(consequence: TripIncident, targetName: string): { headline: string; detail: string | null } {
  const arrival = consequence.expectedArrival
    ? `Ankunft in ${targetName} ca. ${formatTime(consequence.expectedArrival)}`
    : null;
  const delay =
    consequence.assessedDelayMinutes != null
      ? `${consequence.delayAssumed ? 'Etwa ' : ''}${formatMinutes(consequence.assessedDelayMinutes)}`
      : null;
  switch (consequence.category) {
    case 'TOO_LATE':
      if (isUnpassable(consequence)) {
        return { headline: 'Hier kommst du nicht durch', detail: `${targetName} ist auf dieser Route nicht erreichbar.` };
      }
      return {
        headline: 'Du kommst zu spät an',
        detail: arrival && consequence.deadline ? `${arrival} statt ${formatTime(consequence.deadline)}` : arrival,
      };
    case 'DELAYED':
      return { headline: delay ? `${delay} später` : 'Du kommst später an', detail: arrival };
    case 'NO_IMPACT':
      return { headline: 'Keine spürbare Verzögerung', detail: 'Liegt auf deiner Route, bremst dich aber kaum.' };
    default:
      return {
        headline: consequence.impact === 'BLOCKED' ? 'Die Route ist gesperrt' : `Verzögerung (${formatDelay(consequence)})`,
        detail: 'Die Auswirkung auf deine Ankunft wird noch berechnet.',
      };
  }
}
