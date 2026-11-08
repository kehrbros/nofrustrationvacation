import { formatMoney, t } from '../i18n';
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

export { formatMoney };

/** "30.11." (German) or "30/11" (English) from yyyy-mm-dd */
export function formatShortDate(day: string): string {
  const [, month, date] = day.split('-');
  return t('proposal.shortDate', { day: date, month });
}

function arrival(proposal: Proposal): string | null {
  if (!proposal.arrivalAt) {
    return null;
  }
  const time = formatDateTime(proposal.arrivalAt);
  return proposal.meetsDeadline === false ? t('proposal.arrivalLate', { time }) : t('proposal.arrival', { time });
}

function delay(proposal: Proposal): string | null {
  const minutes = proposal.delaySeconds == null ? 0 : Math.round(proposal.delaySeconds / 60);
  return minutes > 0 ? t('consequence.later', { delay: formatMinutes(minutes) }) : null;
}

function conflicts(proposal: Proposal): string | null {
  if (proposal.conflictCount === 0) {
    return null;
  }
  return t('proposal.conflicts', { count: proposal.conflictCount });
}

function stayFacts(stay: ProposalStay): string {
  return [
    formatMoney(stay.price, stay.currency),
    stay.refundable ? t('proposal.refundable') : t('proposal.nonRefundable'),
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
        title: proposal.via ? t('proposal.reroute.titleVia', { via: proposal.via }) : t('proposal.reroute.title'),
        detail: join([arrival(proposal), delay(proposal),
          proposal.distanceMeters != null && formatDistance(proposal.distanceMeters), conflicts(proposal)]),
        effect: proposal.via
          ? t('proposal.reroute.effectVia', { target: setting.targetName, via: proposal.via })
          : t('proposal.reroute.effect', { target: setting.targetName }),
      };
    case 'SHIFT_DEPARTURE':
      return {
        title: proposal.departureAt
          ? t('proposal.shift.titleAt', { time: formatTime(proposal.departureAt) })
          : t('proposal.shift.title'),
        detail: join([proposal.departureAt && t('proposal.shift.departure', { time: formatDateTime(proposal.departureAt) }),
          arrival(proposal), conflicts(proposal)]),
        effect: proposal.departureAt
          ? t('proposal.shift.effectAt', { from: setting.fromName, time: formatDateTime(proposal.departureAt) })
          : t('proposal.shift.effect'),
      };
    case 'STOPOVER':
      return {
        title: stay ? t('proposal.stopover.titleIn', { place: stay.place }) : t('proposal.stopover.title'),
        detail: stay
          ? join([stay.hotelName, stayFacts(stay), t('proposal.stopover.onwards', { date: formatShortDate(stay.checkOut) })])
          : join([arrival(proposal)]),
        effect: [
          stay
            ? t('proposal.stopover.effectPlace', { place: stay.place, target: setting.targetName })
            : t('proposal.stopover.effect', { target: setting.targetName }),
          stay ? t('proposal.stopover.effectBook', { hotel: stay.hotelName }) : null,
          setting.targetStayTitle ? t('proposal.stopover.effectLater', { title: setting.targetStayTitle }) : null,
        ].filter(Boolean).join(' '),
      };
    case 'TARGET_STAY':
      return {
        title: stay ? t('proposal.targetStay.titleHotel', { hotel: stay.hotelName }) : t('proposal.targetStay.title'),
        detail: stay
          ? join([t('proposal.targetStay.distance', { distance: formatDistance(stay.distanceMeters), place: stay.place }),
            stayFacts(stay), `${formatShortDate(stay.checkIn)}–${formatShortDate(stay.checkOut)}`])
          : '',
        effect: [
          stay ? t('proposal.targetStay.effectHotel', { hotel: stay.hotelName }) : t('proposal.targetStay.effect'),
          setting.targetStayTitle && stay?.replacesBookingId != null
            ? t('proposal.targetStay.effectCancel', { title: setting.targetStayTitle })
            : null,
        ].filter(Boolean).join(' '),
      };
    case 'KEEP_ROUTE':
    default:
      return {
        title: t('proposal.keep.title'),
        detail: proposal.arrivalAt
          ? join([arrival(proposal), delay(proposal)])
          : t('proposal.keep.wait'),
        effect: t('proposal.keep.effect'),
      };
  }
}
