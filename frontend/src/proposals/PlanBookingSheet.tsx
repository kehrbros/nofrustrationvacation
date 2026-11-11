import { CheckCircleOutlined } from '@ant-design/icons';
import { Alert, Spin } from 'antd';
import { Fragment, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router';

import Sheet from '../components/Sheet';
import { t } from '../i18n';
import PayPalButton from '../payments/PayPalButton';
import { errorText, HolderForm, holderComplete, money } from '../bookings/AlternativesSheet';
import {
  fetchAlternativesStatus,
  rememberedHolder,
  rememberHolder,
  type AlternativesStatus,
  type Holder,
} from '../bookings/alternativesApi';
import { useTripContext } from '../trips/tripContext';
import { planStepTexts, proposalTexts, type ProposalSetting } from './proposalText';
import {
  abortPlan,
  completePlan,
  fetchPlanExecution,
  planStays,
  startPlanCheckout,
  type PlanExecution,
  type Proposal,
} from './proposalsApi';

/** What the sheet needs to take a plan with hotels, handed over by the proposals dialog. */
export interface PlanBookingState {
  runId: number;
  proposal: Proposal;
  setting: ProposalSetting;
}

type Step =
  | { kind: 'holder' }
  | { kind: 'reserving' }
  | { kind: 'pay'; execution: PlanExecution }
  | { kind: 'applying' }
  | { kind: 'done'; execution: PlanExecution }
  | { kind: 'gone' };

const EMPTY_HOLDER: Holder = { firstName: '', lastName: '', email: '', phone: '' };

/**
 * Takes a plan with hotels in one go, see Docs/incident-plans.md#one-click-execution: guest data, one PayPal
 * approval for all hotels, then the backend books them, changes the trip and pays. Nothing is paid or changed if a
 * hotel fails. The redirect fallback of PayPal comes back to plan/:executionId.
 */
export default function PlanBookingSheet() {
  const { executionId } = useParams();
  const [params] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const { reload, close } = useTripContext();
  const state = location.state as PlanBookingState | null;
  const proposal = state?.proposal ?? null;
  const returned = params.get('planzahlung') !== null;
  const cancelled = params.get('abgebrochen') === '1';

  const [status, setStatus] = useState<AlternativesStatus | null>(null);
  const [holder, setHolder] = useState<Holder>(() => rememberedHolder() ?? EMPTY_HOLDER);
  const [step, setStep] = useState<Step>(executionId ? { kind: 'applying' } : proposal ? { kind: 'holder' } : { kind: 'gone' });
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const loaded = useRef(false);

  useEffect(() => {
    fetchAlternativesStatus().then(setStatus, () => undefined);
  }, []);

  const finished = (execution: PlanExecution) => {
    switch (execution.status) {
      case 'APPLIED':
      case 'APPLIED_PARTLY':
        reload();
        setStep({ kind: 'done', execution });
        break;
      case 'FAILED':
        setError(t('planBooking.failed'));
        setStep(proposal ? { kind: 'holder' } : { kind: 'gone' });
        break;
      case 'APPLYING':
        setStep({ kind: 'applying' });
        break;
      default:
        setStep({ kind: 'pay', execution });
    }
  };

  const complete = (execution: PlanExecution) => {
    setError(null);
    setNotice(null);
    setStep({ kind: 'applying' });
    completePlan(execution.executionId).then(finished, (failure) => {
      setError(errorText(failure));
      fetchPlanExecution(execution.executionId).then(finished, () => setStep({ kind: 'pay', execution }));
    });
  };

  const reserve = async () => {
    if (!state) return;
    setError(null);
    setStep({ kind: 'reserving' });
    try {
      const execution = await startPlanCheckout(state.runId, state.proposal.rank, holder);
      rememberHolder(holder);
      setStep({ kind: 'pay', execution });
    } catch (failure) {
      setError(errorText(failure));
      setStep({ kind: 'holder' });
    }
  };

  // back from PayPal after the redirect fallback, or opened again
  useEffect(() => {
    if (!executionId || loaded.current) {
      return;
    }
    loaded.current = true;
    const id = Number(executionId);
    if (!returned) {
      fetchPlanExecution(id).then(finished, () => setStep({ kind: 'gone' }));
      return;
    }
    navigate('.', { replace: true });
    if (cancelled) {
      abortPlan(id).then(() => setError(t('payment.cancelledAtPayPal')), () => undefined)
        .finally(() => setStep({ kind: 'gone' }));
      return;
    }
    completePlan(id).then(finished, (failure) => {
      setError(errorText(failure));
      fetchPlanExecution(id).then(finished, () => setStep({ kind: 'gone' }));
    });
  }, [executionId, returned, cancelled]); // eslint-disable-line react-hooks/exhaustive-deps

  const stays = proposal ? planStays(proposal) : [];
  const total = proposal?.plan?.extraCost ?? '0';
  const currency = proposal?.plan?.costCurrency ?? stays[0]?.currency ?? 'EUR';
  const title = step.kind === 'done'
    ? (step.execution.status === 'APPLIED' ? t('planBooking.applied') : t('planBooking.partly'))
    : proposal && state ? proposalTexts(proposal, state.setting).title : t('planBooking.title');
  return (
    <Sheet open onClose={close} eyebrow={t('planBooking.eyebrow')} title={title} height="90vh" footer={footer()}>
      {status && !status.bookingAvailable && step.kind === 'holder' && (
        <Alert type="info" showIcon message={t('stay.unavailable')} />
      )}
      {error && <Alert type="error" showIcon message={error} closable onClose={() => setError(null)} />}
      {notice && <Alert type="info" showIcon message={notice} closable onClose={() => setNotice(null)} />}
      {(step.kind === 'holder' || step.kind === 'pay') && proposal && state && (
        <div className="va-form">
          <ol className="va-plan-steps">
            {planStepTexts(proposal, state.setting).map((line, index) => <li key={index}>{line}</li>)}
          </ol>
          <p className="va-small va-muted">{t('planBooking.intro')}</p>
        </div>
      )}
      {step.kind === 'holder' && (
        <HolderForm candidate={{ name: stays.map((stay) => stay.hotelName).join(', '), price: total, currency }}
          holder={holder} onChange={setHolder} />
      )}
      {step.kind === 'reserving' && (
        <div className="va-empty va-column va-gap"><Spin /><span className="va-muted">{t('planBooking.preparing')}</span></div>
      )}
      {step.kind === 'pay' && (
        <dl className="va-facts">
          {step.execution.hotels.map((hotel) => (
            <Fragment key={hotel.hotelName}>
              <dt>{hotel.hotelName}</dt>
              <dd>{money(hotel.price, hotel.currency)}</dd>
            </Fragment>
          ))}
          {step.execution.total && step.execution.currency && (
            <>
              <dt>{t('planBooking.total')}</dt>
              <dd className="va-strong">{money(step.execution.total, step.execution.currency)}</dd>
            </>
          )}
        </dl>
      )}
      {step.kind === 'applying' && (
        <div className="va-empty va-column va-gap"><Spin /><span className="va-muted">{t('planBooking.applying')}</span></div>
      )}
      {step.kind === 'done' && (
        <div className="va-form">
          {step.execution.status === 'APPLIED' ? (
            <div className="va-success">
              <CheckCircleOutlined aria-hidden="true" />
              <span className="va-column">
                <span className="va-strong">{t('planBooking.appliedText')}</span>
                {step.execution.hotels.map((hotel) => (
                  <span key={hotel.hotelName} className="va-small">{hotel.hotelName} · {money(hotel.price, hotel.currency)}</span>
                ))}
              </span>
            </div>
          ) : (
            <Alert type="warning" showIcon message={t('planBooking.partlyText')} />
          )}
          {step.execution.todosAdded > 0 && (
            <p className="va-small va-muted">{t('planBooking.todos', { count: step.execution.todosAdded })}</p>
          )}
        </div>
      )}
      {step.kind === 'gone' && <p className="va-muted">{t('planBooking.gone')}</p>}
    </Sheet>
  );

  function footer() {
    switch (step.kind) {
      case 'holder':
        return (
          <button type="button" className="va-button va-block" disabled={!holderComplete(holder) || !status?.bookingAvailable}
            onClick={() => void reserve()}>
            {t('payment.continue')}
          </button>
        );
      case 'pay':
        return (
          <div className="va-column va-gap">
            <PayPalButton
              clientId={status?.payPalClientId ?? null}
              currency={step.execution.currency ?? currency}
              orderId={step.execution.orderId}
              approveUrl={step.execution.approveUrl}
              onApprove={() => complete(step.execution)}
              onCancel={() => setNotice(t('payment.cancelled'))}
              onError={() => setError(t('payment.startFailed'))}
            />
            {!step.execution.orderId && !step.execution.approveUrl && (
              <button type="button" className="va-button va-block" onClick={() => complete(step.execution)}>
                {t('common.retry')}
              </button>
            )}
          </div>
        );
      case 'done':
      case 'gone':
        return <button type="button" className="va-button va-block" onClick={close}>{t('payment.toTrip')}</button>;
      default:
        return undefined;
    }
  }
}
