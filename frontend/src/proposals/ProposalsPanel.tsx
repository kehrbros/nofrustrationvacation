import { CheckCircleOutlined, CheckOutlined, HomeOutlined, NodeIndexOutlined, ReloadOutlined, ClockCircleOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';

import KiwiLoader from '../components/KiwiLoader';
import { t } from '../i18n';
import type { StayBookingState } from '../bookings/StayBookingSheet';
import { TripsApiError } from '../trips/tripsApi';
import { formatAgo } from '../trips/time';
import { formatMoney, planStepTexts, proposalTexts, type ProposalSetting } from './proposalText';
import type { PlanBookingState } from './PlanBookingSheet';
import {
  acceptProposal,
  dismissProposals,
  fetchRouteRun,
  fetchRun,
  isRunning,
  planStays,
  startRun,
  type Proposal,
  type ProposalKind,
  type ProposalRun,
} from './proposalsApi';

const POLL_MS = 2500;

/** Why the solutions are searched again right when the panel opens: a hotel of the plan taken was gone or dearer. */
export type SearchAgainReason = 'OFFER_UNAVAILABLE' | 'PRICE_EXCEEDED';

/** Router state of the solutions dialog when taking a plan failed, see {@link SearchAgainReason}. */
export interface SearchAgainState {
  searchAgain: SearchAgainReason;
}

/**
 * The solutions for a route hit by an incident: searched in the background when asked for (again at any time), the
 * best first. Taking one changes the trip (route, departure, a night on the way) or leads to booking the hotel;
 * opening "plan myself" dismisses open ones. See Docs/advisor.md#frontend.
 */
export default function ProposalsPanel({ tripId, routeId, incidentId, actionable, setting, onChanged, manualOpen,
  onToggleManual, searchAgain }: {
  tripId: number;
  routeId: number;
  incidentId: number;
  /**
   * The incident still costs time on the route (DELAYED or TOO_LATE): only then can solutions be searched for. Otherwise
   * the panel says that there is nothing to do and shows the last solutions without a search.
   */
  actionable: boolean;
  setting: ProposalSetting;
  /** The trip changed, load it again. */
  onChanged: () => void;
  /** The ways to plan again by hand are shown (below the panel, by the caller). */
  manualOpen: boolean;
  onToggleManual: (open: boolean) => void;
  /** Search again right away (instead of showing the last solutions) and say why. */
  searchAgain?: SearchAgainReason | null;
}) {
  const navigate = useNavigate();
  // undefined: loading
  const [run, setRun] = useState<ProposalRun | null | undefined>(undefined);
  const [selected, setSelected] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  // the server knew better than the trip loaded: the incident no longer costs time
  const [nothingToDo, setNothingToDo] = useState(false);
  const canSearch = actionable && !nothingToDo;
  const [searchingAgain, setSearchingAgain] = useState<SearchAgainReason | null>(null);
  const searchedAgain = useRef(false);

  useEffect(() => {
    if (searchAgain && actionable && !searchedAgain.current) {
      searchedAgain.current = true;
      setSearchingAgain(searchAgain);
      void search();
      return;
    }
    let active = true;
    fetchRouteRun(routeId).then((latest) => active && setRun(latest), () => active && setRun(null));
    return () => {
      active = false;
    };
  }, [routeId]); // eslint-disable-line react-hooks/exhaustive-deps

  // while searching: ask again until the run has ended
  useEffect(() => {
    if (!run || !isRunning(run)) {
      return;
    }
    const timer = window.setTimeout(() => {
      fetchRun(run.runId).then(setRun, () => undefined);
    }, POLL_MS);
    return () => window.clearTimeout(timer);
  }, [run]);

  async function search() {
    setBusy(true);
    setError(null);
    setDone(null);
    setSelected(null);
    try {
      const runId = await startRun(routeId, incidentId);
      setRun(await fetchRun(runId));
    } catch (failure) {
      if (failure instanceof TripsApiError && failure.code === 'NO_ACTIVE_CONSEQUENCE') {
        setNothingToDo(true);
        onChanged();
      } else {
        setError(t('proposals.searchFailed'));
      }
    } finally {
      setBusy(false);
      // searched before anything was loaded: show the empty panel rather than loading forever
      setRun((current) => (current === undefined ? null : current));
    }
  }

  const take = async (proposal: Proposal) => {
    if (!run) return;
    if (proposal.kind === 'PLAN' && planStays(proposal).length > 0) {
      setSearchingAgain(null);
      const state: PlanBookingState = { runId: run.runId, proposal, setting };
      navigate(`/reisen/${tripId}/plan`, { state });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const accepted = await acceptProposal(run.runId, proposal.rank);
      onChanged();
      if ((accepted.kind === 'STOPOVER' || accepted.kind === 'TARGET_STAY') && proposal.stay && accepted.stayStopId != null) {
        const state: StayBookingState = { stay: proposal.stay };
        navigate(`/reisen/${tripId}/stops/${accepted.stayStopId}/unterkunft`, { state });
        return;
      }
      setRun({ ...run, decision: 'ACCEPTED', chosenRank: proposal.rank });
      setSelected(null);
      setDone(doneText(accepted.kind));
    } catch (failure) {
      setError(failure instanceof TripsApiError && failure.code === 'PROPOSAL_NOT_APPLICABLE'
        ? t('proposals.notApplicable')
        : t('common.failedTryAgain'));
    } finally {
      setBusy(false);
    }
  };

  /** Opening "plan myself" while proposals wait for a decision dismisses them. */
  const toggleManual = async () => {
    if (manualOpen) {
      onToggleManual(false);
      return;
    }
    setSelected(null);
    onToggleManual(true);
    if (run && !isRunning(run) && run.decision == null && run.proposals.length > 0) {
      try {
        setRun(await dismissProposals(run.runId));
      } catch {
        // planning by hand works without it
      }
    }
  };

  if (run === undefined) {
    return searchingAgain
      ? <div className="va-note va-row va-gap" role="status"><KiwiLoader size="small" decorative /><span>{t(`proposals.searchingAgain.${searchingAgain}`)}</span></div>
      : <div className="va-empty"><KiwiLoader size="small" decorative /> {t('proposals.loading')}</div>;
  }

  const result = run && (run.status === 'COMPLETED' || run.status === 'COMPLETED_WITHOUT_MODEL') ? run : null;
  return (
    <div className="va-column va-gap">
      {error && <Alert type="error" showIcon message={error} closable onClose={() => setError(null)} />}
      {searchingAgain && isRunning(run) && <Alert type="info" showIcon message={t(`proposals.searchingAgain.${searchingAgain}`)} closable
        onClose={() => setSearchingAgain(null)} />}
      {done && <div className="va-success"><CheckCircleOutlined aria-hidden="true" /><span>{done}</span></div>}

      {!canSearch && (
        <div className="va-success" role="status"><CheckCircleOutlined aria-hidden="true" /><span>{t('proposals.noActionNeeded')}</span></div>
      )}
      {run === null && canSearch && (
        <p className="va-muted">{t('proposals.intro')}</p>
      )}
      {isRunning(run) && (
        <div className="va-note va-row va-gap" role="status" aria-live="polite">
          <KiwiLoader size="small" decorative />
          <span className="va-column">
            <span className="va-strong">{t('proposals.searching')}</span>
            <span className="va-small">{t('proposals.searchingHint')}</span>
          </span>
        </div>
      )}
      {run?.status === 'FAILED' && <p className="va-muted">{t('proposals.runFailed')}</p>}
      {result && result.proposals.length === 0 && <p className="va-muted">{t('proposals.none')}</p>}
      {canSearch && result && result.stale && result.proposals.length > 0 && (
        <Alert type="warning" showIcon message={t('proposals.stale')} />
      )}
      {result && result.decision === 'DISMISSED' && result.proposals.length > 0 && (
        <p className="va-small va-muted">{t('proposals.dismissed')}</p>
      )}

      {result && result.proposals.length > 0 && (
        <ul className="va-alternatives">
          {result.proposals.map((proposal) => (
            <li key={proposal.rank}>
              <ProposalCard
                proposal={proposal}
                setting={setting}
                chosen={result.decision === 'ACCEPTED' && result.chosenRank === proposal.rank}
                open={selected === proposal.rank}
                busy={busy}
                onOpen={() => setSelected(selected === proposal.rank ? null : proposal.rank)}
                onTake={() => void take(proposal)}
              />
            </li>
          ))}
        </ul>
      )}

      <div className="va-row va-gap va-wrap">
        {!isRunning(run) && canSearch && (
          <button type="button" className={`va-button ${result && result.proposals.length > 0 ? 'va-button-outline' : ''}`}
            disabled={busy} onClick={() => void search()}>
            <ReloadOutlined aria-hidden="true" /> {run === null ? t('proposals.search') : t('proposals.refresh')}
          </button>
        )}
        <button type="button" className="va-text-button" aria-expanded={manualOpen} aria-controls="va-manual-planning"
          onClick={() => void toggleManual()}>
          {canSearch && result && result.proposals.length > 0 ? t('proposals.noneFits') : t('proposals.planMyself')} {manualOpen ? '↑' : '↓'}
        </button>
      </div>
      {run && !isRunning(run) && <span className="va-small va-muted">{t('proposals.asOf', { ago: formatAgo(run.createdAt) })}</span>}
    </div>
  );
}

function ProposalCard({ proposal, setting, chosen, open, busy, onOpen, onTake }: {
  proposal: Proposal;
  setting: ProposalSetting;
  chosen: boolean;
  open: boolean;
  busy: boolean;
  onOpen: () => void;
  onTake: () => void;
}) {
  const texts = proposalTexts(proposal, setting);
  const steps = proposal.kind === 'PLAN' ? planStepTexts(proposal, setting) : [];
  return (
    <div className={`va-proposal ${open ? 'va-proposal-open' : ''}`}>
      <button type="button" className="va-alternative va-proposal-head" aria-expanded={open} onClick={onOpen}>
        <span className="va-booking-icon" aria-hidden="true">{icon(proposal)}</span>
        <span className="va-column va-grow">
          <span className="va-strong">{texts.title}</span>
          <span className="va-small va-muted">{texts.detail}</span>
        </span>
        {chosen ? <span className="va-chip va-chip-ink"><CheckOutlined aria-hidden="true" /> {t('proposals.chosen')}</span>
          : proposal.rank === 1 && <span className="va-chip va-chip-soft">{t('proposals.recommended')}</span>}
      </button>
      {open && (
        <div className="va-proposal-confirm">
          {steps.length > 0 && (
            <ol className="va-plan-steps">
              {steps.map((line, index) => <li key={index}>{line}</li>)}
            </ol>
          )}
          <p>{texts.effect}</p>
          <button type="button" className="va-button va-block" disabled={busy} onClick={onTake}>
            {busy && <><KiwiLoader size="small" decorative />{' '}</>}{busy ? t('proposals.taking') : takeLabel(proposal)}
          </button>
        </div>
      )}
    </div>
  );
}

function icon(proposal: Proposal) {
  if (proposal.kind === 'PLAN') {
    return planStays(proposal).length > 0 ? <HomeOutlined /> : <NodeIndexOutlined />;
  }
  switch (proposal.kind) {
    case 'STOPOVER':
    case 'TARGET_STAY':
      return <HomeOutlined />;
    case 'SHIFT_DEPARTURE':
      return <ClockCircleOutlined />;
    default:
      return <NodeIndexOutlined />;
  }
}

function takeLabel(proposal: Proposal): string {
  const plan = proposal.plan;
  if (proposal.kind === 'PLAN') {
    return plan && plan.costCurrency && Number(plan.extraCost) > 0
      ? t('proposals.take.PLAN_paid', { amount: formatMoney(plan.extraCost, plan.costCurrency) })
      : t('proposals.take.PLAN');
  }
  switch (proposal.kind) {
    case 'STOPOVER':
      return t('proposals.take.STOPOVER');
    case 'TARGET_STAY':
      return t('proposals.take.TARGET_STAY');
    case 'KEEP_ROUTE':
      return t('proposals.take.KEEP_ROUTE');
    default:
      return t('proposals.take.other');
  }
}

function doneText(kind: ProposalKind): string {
  switch (kind) {
    case 'REROUTE':
      return t('proposals.done.REROUTE');
    case 'SHIFT_DEPARTURE':
      return t('proposals.done.SHIFT_DEPARTURE');
    case 'PLAN':
      return t('proposals.done.PLAN');
    default:
      return t('proposals.done.other');
  }
}
