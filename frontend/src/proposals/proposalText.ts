import { formatMoney, t } from '../i18n';
import { formatMinutes } from '../trips/incidentText';
import { formatDistance } from '../trips/routeOptionText';
import { formatDateTime, formatDay, formatTime } from '../trips/time';
import { planStays, type PlanStep, type Proposal, type ProposalStay } from './proposalsApi';

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
    case 'PLAN':
      return planTexts(proposal, setting);
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

function planTitle(proposal: Proposal): string {
  const night = proposal.steps.find((step) => step.kind === 'OVERNIGHT');
  const replace = proposal.steps.find((step) => step.kind === 'REPLACE_STAY');
  const drive = proposal.steps.find((step) => step.kind === 'DRIVE');
  if (proposal.plan?.newTargetName) {
    return t('proposal.plan.titleOther', { place: proposal.plan.newTargetName });
  }
  if (night) {
    return t('proposal.plan.titleNight', { place: night.placeName ?? night.stay?.place ?? '' });
  }
  if (replace?.stay) {
    return t('proposal.targetStay.titleHotel', { hotel: replace.stay.hotelName });
  }
  if (drive?.routeKind === 'REROUTE') {
    return drive.via ? t('proposal.reroute.titleVia', { via: drive.via }) : t('proposal.reroute.title');
  }
  if (drive?.routeKind === 'SHIFT_DEPARTURE' && drive.departureAt) {
    return t('proposal.shift.titleAt', { time: formatTime(drive.departureAt) });
  }
  return t('proposal.plan.titleNotify');
}

function planCost(proposal: Proposal): string | null {
  const plan = proposal.plan;
  if (!plan) {
    return null;
  }
  return Number(plan.extraCost) > 0 && plan.costCurrency
    ? t('proposal.plan.toPay', { amount: formatMoney(plan.extraCost, plan.costCurrency) })
    : t('proposal.plan.free');
}

/** One line per step of the plan, in order. */
export function planStepTexts(proposal: Proposal, setting: ProposalSetting): string[] {
  const steps = proposal.steps;
  return steps.map((step, index) => stepText(step, steps[index + 1], setting)).filter((line): line is string => !!line);
}

function stepText(step: PlanStep, next: PlanStep | undefined, setting: ProposalSetting): string | null {
  const stay = step.stay;
  const price = stay ? formatMoney(stay.price, stay.currency) : '';
  switch (step.kind) {
    case 'DRIVE': {
      const time = step.arrivalAt ? formatDateTime(step.arrivalAt) : '';
      if (next?.kind === 'OVERNIGHT') {
        return t('proposal.plan.stepDriveTo', { place: next.placeName ?? next.stay?.place ?? '', time });
      }
      if (step.routeKind === 'SHIFT_DEPARTURE' && step.departureAt) {
        return t('proposal.plan.stepDriveAt', { time: formatDateTime(step.departureAt), arrival: time });
      }
      if (step.routeKind === 'REROUTE' && step.via) {
        return t('proposal.plan.stepDriveVia', { via: step.via, time });
      }
      return t('proposal.plan.stepDrive', { time });
    }
    case 'OVERNIGHT':
      return stay ? t('proposal.plan.stepNight', { hotel: stay.hotelName, price }) : null;
    case 'CONTINUE': {
      if (!step.departureAt) {
        return null;
      }
      const values = {
        date: formatDay(step.departureAt),
        time: formatTime(step.departureAt),
        arrival: step.arrivalAt ? formatDateTime(step.arrivalAt) : '',
      };
      return step.waitedFor ? t('proposal.plan.stepContinueWait', values) : t('proposal.plan.stepContinue', values);
    }
    case 'KEEP_BOOKING': {
      const title = setting.targetStayTitle ?? t('proposal.plan.yourStay');
      return step.arrivalOn
        ? t('proposal.plan.stepNotifyDay', { title, date: formatShortDate(step.arrivalOn) })
        : t('proposal.plan.stepNotifyLate', { title });
    }
    case 'REPLACE_STAY':
      return stay ? t('proposal.plan.stepReplace', { hotel: stay.hotelName, price }) : null;
    case 'NEW_TARGET':
      return stay ? t('proposal.plan.stepNewTarget', {
        target: setting.targetName, place: step.placeName ?? stay.place, hotel: stay.hotelName, price,
      }) : null;
    default:
      return null;
  }
}

function planTexts(proposal: Proposal, setting: ProposalSetting): ProposalTexts {
  const plan = proposal.plan;
  const paid = planStays(proposal).length > 0 && plan != null && plan.costCurrency != null;
  const replaces = planStays(proposal).some((stay) => stay.replacesBookingId != null);
  return {
    title: planTitle(proposal),
    detail: join([arrival(proposal), delay(proposal),
      plan && plan.extraNights > 0 && t('proposal.plan.nights', { count: plan.extraNights }), planCost(proposal)]),
    effect: [
      paid && plan?.costCurrency
        ? t('proposal.plan.effectPaid', { amount: formatMoney(plan.extraCost, plan.costCurrency) })
        : t('proposal.plan.effectFree'),
      replaces && setting.targetStayTitle ? t('proposal.plan.effectReplaced', { title: setting.targetStayTitle }) : null,
      plan?.affectsFollowing ? t('proposal.plan.effectFollowing') : null,
    ].filter(Boolean).join(' '),
  };
}
