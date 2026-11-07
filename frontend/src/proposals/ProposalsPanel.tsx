import { CheckCircleOutlined, CheckOutlined, HomeOutlined, LoadingOutlined, NodeIndexOutlined, ReloadOutlined, ClockCircleOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';

import type { StayBookingState } from '../bookings/StayBookingSheet';
import { TripsApiError } from '../trips/tripsApi';
import { formatAgo } from '../trips/time';
import { proposalTexts, type ProposalSetting } from './proposalText';
import {
  acceptProposal,
  dismissProposals,
  fetchRouteRun,
  fetchRun,
  isRunning,
  startRun,
  type Proposal,
  type ProposalKind,
  type ProposalRun,
} from './proposalsApi';

const POLL_MS = 2500;

/**
 * The solutions for a route hit by an incident: searched in the background when asked for (again at any time), the
 * best first. Taking one changes the trip (route, departure, a night on the way) or leads to booking the hotel;
 * "plan myself" dismisses them. See Docs/advisor.md#frontend.
 */
export default function ProposalsPanel({ tripId, routeId, incidentId, setting, onChanged, onPlanMyself }: {
  tripId: number;
  routeId: number;
  incidentId: number;
  setting: ProposalSetting;
  /** The trip changed, load it again. */
  onChanged: () => void;
  /** Dismissed: show the ways to plan again by hand. */
  onPlanMyself: () => void;
}) {
  const navigate = useNavigate();
  // undefined: loading
  const [run, setRun] = useState<ProposalRun | null | undefined>(undefined);
  const [selected, setSelected] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    fetchRouteRun(routeId).then((latest) => active && setRun(latest), () => active && setRun(null));
    return () => {
      active = false;
    };
  }, [routeId]);

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

  const search = async () => {
    setBusy(true);
    setError(null);
    setDone(null);
    setSelected(null);
    try {
      const runId = await startRun(routeId, incidentId);
      setRun(await fetchRun(runId));
    } catch (failure) {
      setError(failure instanceof TripsApiError && failure.code === 'NO_ACTIVE_CONSEQUENCE'
        ? 'Für diesen Hinweis gibt es gerade nichts umzuplanen.'
        : 'Die Suche konnte nicht gestartet werden. Bitte versuche es noch einmal.');
    } finally {
      setBusy(false);
    }
  };

  const take = async (proposal: Proposal) => {
    if (!run) return;
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
        ? 'Das geht so nicht mehr, die Reise hat sich inzwischen geändert. Bitte suche neu.'
        : 'Das hat nicht geklappt. Bitte versuche es noch einmal.');
    } finally {
      setBusy(false);
    }
  };

  const dismiss = async () => {
    if (run && !isRunning(run) && run.decision !== 'DISMISSED') {
      try {
        setRun(await dismissProposals(run.runId));
      } catch {
        // planning by hand works without it
      }
    }
    setSelected(null);
    onPlanMyself();
  };

  if (run === undefined) {
    return <div className="va-empty"><LoadingOutlined aria-hidden="true" /> Lösungen werden geladen …</div>;
  }

  const result = run && (run.status === 'COMPLETED' || run.status === 'COMPLETED_WITHOUT_MODEL') ? run : null;
  return (
    <div className="va-column va-gap">
      {error && <Alert type="error" showIcon message={error} closable onClose={() => setError(null)} />}
      {done && <div className="va-success"><CheckCircleOutlined aria-hidden="true" /><span>{done}</span></div>}

      {run === null && (
        <p className="va-muted">Wir suchen für dich, wie du trotzdem gut ankommst: andere Strecke, andere Abfahrt oder eine Übernachtung.</p>
      )}
      {isRunning(run) && (
        <div className="va-note va-row va-gap" role="status" aria-live="polite">
          <LoadingOutlined aria-hidden="true" />
          <span className="va-column">
            <span className="va-strong">Lösungen werden gesucht …</span>
            <span className="va-small">Das dauert meist ein bis zwei Minuten. Du kannst den Dialog schließen.</span>
          </span>
        </div>
      )}
      {run?.status === 'FAILED' && <p className="va-muted">Die Suche hat nicht geklappt. Versuche es noch einmal.</p>}
      {result && result.proposals.length === 0 && <p className="va-muted">Es wurde keine passende Lösung gefunden.</p>}
      {result && result.stale && result.proposals.length > 0 && (
        <Alert type="warning" showIcon message="Die Lage hat sich seitdem geändert, die Zeiten stimmen vielleicht nicht mehr." />
      )}
      {result && result.decision === 'DISMISSED' && result.proposals.length > 0 && (
        <p className="va-small va-muted">Du hast diese Vorschläge verworfen. Du kannst trotzdem einen übernehmen oder neu suchen.</p>
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
        {!isRunning(run) && (
          <button type="button" className={`va-button ${result && result.proposals.length > 0 ? 'va-button-outline' : ''}`}
            disabled={busy} onClick={() => void search()}>
            <ReloadOutlined aria-hidden="true" /> {run === null ? 'Lösungen suchen' : 'Neu suchen'}
          </button>
        )}
        <button type="button" className="va-text-button" onClick={() => void dismiss()}>
          Keine passt, ich plane selbst um
        </button>
      </div>
      {run && !isRunning(run) && <span className="va-small va-muted">Stand {formatAgo(run.createdAt)}</span>}
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
  return (
    <div className={`va-proposal ${open ? 'va-proposal-open' : ''}`}>
      <button type="button" className="va-alternative va-proposal-head" aria-expanded={open} onClick={onOpen}>
        <span className="va-booking-icon" aria-hidden="true">{icon(proposal.kind)}</span>
        <span className="va-column va-grow">
          <span className="va-strong">{texts.title}</span>
          <span className="va-small va-muted">{texts.detail}</span>
        </span>
        {chosen ? <span className="va-chip va-chip-ink"><CheckOutlined aria-hidden="true" /> Gewählt</span>
          : proposal.rank === 1 && <span className="va-chip va-chip-soft">Empfohlen</span>}
      </button>
      {open && (
        <div className="va-proposal-confirm">
          <p>{texts.effect}</p>
          <button type="button" className="va-button va-block" disabled={busy} onClick={onTake}>
            {busy ? 'Wird übernommen …' : takeLabel(proposal.kind)}
          </button>
        </div>
      )}
    </div>
  );
}

function icon(kind: ProposalKind) {
  switch (kind) {
    case 'STOPOVER':
    case 'TARGET_STAY':
      return <HomeOutlined />;
    case 'SHIFT_DEPARTURE':
      return <ClockCircleOutlined />;
    default:
      return <NodeIndexOutlined />;
  }
}

function takeLabel(kind: ProposalKind): string {
  switch (kind) {
    case 'STOPOVER':
      return 'Übernehmen und Hotel buchen';
    case 'TARGET_STAY':
      return 'Weiter zur Buchung';
    case 'KEEP_ROUTE':
      return 'So lassen';
    default:
      return 'Übernehmen';
  }
}

function doneText(kind: ProposalKind): string {
  switch (kind) {
    case 'REROUTE':
      return 'Übernommen. Die Route ist neu geplant.';
    case 'SHIFT_DEPARTURE':
      return 'Übernommen. Die Abfahrt ist geändert, die Route neu geplant.';
    default:
      return 'Alles klar, an der Reise ändert sich nichts.';
  }
}
