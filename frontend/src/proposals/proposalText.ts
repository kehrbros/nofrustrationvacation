import { formatMinutes } from '../trips/incidentText';
import { formatDistance } from '../trips/routeOptionText';
import { formatDateTime, formatTime } from '../trips/time';
import type { Proposal, ProposalStay } from './proposalsApi';

/**
 * Texts of the proposals. Built here from the numbers of the backend, the reasons of the model are not shown (they are
 * English, and the app does not talk about the AI, see Docs/advisor.md#frontend).
 */

export interface ProposalTexts {
  title: string;
  detail: string;
  /** What taking it changes, shown before confirming. */
  effect: string;
}

export interface ProposalSetting {
  /** Start and target of the route. */
  fromName: string;
  targetName: string;
  /** Accommodation booked at the target stop, if any, e.g. "Hotel Adler". */
  targetStayTitle: string | null;
}

/** "189,00 €" */
export function formatMoney(amount: string, currency: string): string {
  try {
    return new Intl.NumberFormat('de-DE', { style: 'currency', currency }).format(Number(amount));
  } catch {
    return `${amount} ${currency}`;
  }
}

/** "30.11." from yyyy-mm-dd */
export function formatShortDate(day: string): string {
  const [, month, date] = day.split('-');
  return `${date}.${month}.`;
}

function arrival(proposal: Proposal): string | null {
  if (!proposal.arrivalAt) {
    return null;
  }
  const late = proposal.meetsDeadline === false ? ', zu spät' : '';
  return `Ankunft ${formatDateTime(proposal.arrivalAt)}${late}`;
}

function delay(proposal: Proposal): string | null {
  const minutes = proposal.delaySeconds == null ? 0 : Math.round(proposal.delaySeconds / 60);
  return minutes > 0 ? `${formatMinutes(minutes)} später` : null;
}

function conflicts(proposal: Proposal): string | null {
  if (proposal.conflictCount === 0) {
    return null;
  }
  return proposal.conflictCount === 1 ? 'noch 1 Hinweis unterwegs' : `noch ${proposal.conflictCount} Hinweise unterwegs`;
}

function stayFacts(stay: ProposalStay): string {
  return [
    formatMoney(stay.price, stay.currency),
    stay.refundable ? 'kostenlos stornierbar' : 'nicht stornierbar',
  ].join(' · ');
}

function join(parts: (string | null | false | undefined)[]): string {
  return parts.filter(Boolean).join(' · ');
}

export function proposalTexts(proposal: Proposal, setting: ProposalSetting): ProposalTexts {
  const stay = proposal.stay;
  switch (proposal.kind) {
    case 'REROUTE':
      return {
        title: proposal.via ? `Umfahrung über ${proposal.via}` : 'Andere Strecke',
        detail: join([arrival(proposal), delay(proposal),
          proposal.distanceMeters != null && formatDistance(proposal.distanceMeters), conflicts(proposal)]),
        effect: `Die Route nach ${setting.targetName} wird ${proposal.via ? `über ${proposal.via} ` : ''}neu geplant.`,
      };
    case 'SHIFT_DEPARTURE':
      return {
        title: proposal.departureAt ? `Um ${formatTime(proposal.departureAt)} losfahren` : 'Andere Abfahrt',
        detail: join([proposal.departureAt && `Abfahrt ${formatDateTime(proposal.departureAt)}`, arrival(proposal),
          conflicts(proposal)]),
        effect: proposal.departureAt
          ? `Die Abfahrt in ${setting.fromName} wird auf ${formatDateTime(proposal.departureAt)} gesetzt.`
          : 'Die Abfahrt wird geändert.',
      };
    case 'STOPOVER':
      return {
        title: stay ? `Übernachten in ${stay.place}` : 'Übernachten unterwegs',
        detail: stay
          ? join([stay.hotelName, stayFacts(stay), `weiter am ${formatShortDate(stay.checkOut)}`])
          : join([arrival(proposal)]),
        effect: [
          `${stay?.place ?? 'Ein Stop'} kommt als neuer Stop vor ${setting.targetName}, die Route wird geteilt und neu geplant.`,
          stay ? `Danach kannst du ${stay.hotelName} direkt hier buchen und mit PayPal bezahlen.` : null,
          setting.targetStayTitle
            ? `„${setting.targetStayTitle}“ erwartet dich dann einen Tag später, das kommt auf deine To-do-Liste.`
            : null,
        ].filter(Boolean).join(' '),
      };
    case 'TARGET_STAY':
      return {
        title: stay ? `Andere Unterkunft: ${stay.hotelName}` : 'Andere Unterkunft',
        detail: stay
          ? join([`${formatDistance(stay.distanceMeters)} von ${stay.place}`, stayFacts(stay),
            `${formatShortDate(stay.checkIn)}–${formatShortDate(stay.checkOut)}`])
          : '',
        effect: [
          stay ? `Du buchst ${stay.hotelName} und bezahlst mit PayPal.` : 'Du buchst eine andere Unterkunft.',
          setting.targetStayTitle && stay?.replacesBookingId != null
            ? `„${setting.targetStayTitle}“ kommt danach auf deine To-do-Liste, damit du sie stornieren kannst.`
            : null,
        ].filter(Boolean).join(' '),
      };
    case 'KEEP_ROUTE':
    default:
      return {
        title: 'Route beibehalten',
        detail: proposal.arrivalAt
          ? join([arrival(proposal), delay(proposal)])
          : 'Warten, bis die Strecke wieder frei ist',
        effect: 'An der Reise ändert sich nichts.',
      };
  }
}
