import { t } from '../i18n';
import type { Category, Impact, IncidentType, TripIncident } from './model';
import { formatTime } from './time';

export function incidentTypeLabel(type: IncidentType): string {
  return t(`incident.type.${type}`);
}

const MAX_TITLE_LENGTH = 60;

/** Whether the description is short enough to serve as title, e.g. "Vollsperrung A22". */
export function descriptionIsTitle(incident: TripIncident): boolean {
  const description = incident.description?.trim();
  return !!description && description.length <= MAX_TITLE_LENGTH;
}

/** Short title for chips on the map and list entries: a short description, otherwise the kind of incident. */
export function incidentTitle(incident: TripIncident): string {
  return descriptionIsTitle(incident) ? incident.description!.trim() : incidentTypeLabel(incident.type);
}

export function impactLabel(impact: Impact): string {
  return impact === 'BLOCKED' ? t('incident.blocked') : t('incident.delay');
}

/** "45 Min.", "1 Std. 30 Min.", or a fallback when unknown. */
export function formatDelay(incident: TripIncident): string {
  if (incident.delayMinutes == null) {
    return incident.impact === 'BLOCKED' ? t('incident.delayUnknownBlocked') : t('incident.delayUnknown');
  }
  return formatMinutes(incident.delayMinutes);
}

/** "45 Min.", "2 Std.", "1 Std. 30 Min." */
export function formatMinutes(total: number): string {
  const hours = Math.floor(total / 60);
  const minutes = total % 60;
  if (hours === 0) {
    return t('incident.minutes', { minutes });
  }
  return minutes === 0 ? t('incident.hours', { hours }) : t('incident.hoursMinutes', { hours, minutes });
}

/** Label of the effect on the arrival, e.g. for the chip in the alternatives dialog. */
export function categoryLabel(category: Category | null): string {
  return category ? t(`incident.category.${category}`) : t('incident.categoryPending');
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
    ? t('consequence.arrival', { target: targetName, time: formatTime(consequence.expectedArrival) })
    : null;
  const delay =
    consequence.assessedDelayMinutes != null
      ? consequence.delayAssumed
        ? t('consequence.aboutDelay', { delay: formatMinutes(consequence.assessedDelayMinutes) })
        : formatMinutes(consequence.assessedDelayMinutes)
      : null;
  switch (consequence.category) {
    case 'TOO_LATE':
      if (isUnpassable(consequence)) {
        return { headline: t('consequence.unpassable'), detail: t('consequence.unpassableDetail', { target: targetName }) };
      }
      return {
        headline: t('consequence.tooLate'),
        detail: arrival && consequence.deadline
          ? t('consequence.insteadOf', { arrival, deadline: formatTime(consequence.deadline) })
          : arrival,
      };
    case 'DELAYED':
      return { headline: delay ? t('consequence.later', { delay }) : t('consequence.laterUnknown'), detail: arrival };
    case 'NO_IMPACT':
      return { headline: t('consequence.noImpact'), detail: t('consequence.noImpactDetail') };
    default:
      return {
        headline: consequence.impact === 'BLOCKED'
          ? t('consequence.blocked')
          : t('consequence.pendingDelay', { delay: formatDelay(consequence) }),
        detail: t('consequence.pendingDetail'),
      };
  }
}
