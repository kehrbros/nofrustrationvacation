import { Button, Flex, Result, Spin } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';

import { takeReturnTo } from '../../accounting/payPalLink';
import { problemMessage } from '../../accounting/problems';
import OnboardingShell from '../../components/OnboardingShell';
import { completePayPalLink, type PayoutAccountStatusDto } from '../../generated/api';
import { PAYOUT_ACCOUNT_PATH, roleHome } from '../../role/navigation';

type State = { kind: 'loading' } | { kind: 'linked'; account: PayoutAccountStatusDto } | { kind: 'failed'; message: string };

/**
 * Redirect URI of "Log in with PayPal": passes code and state to the backend, which links the account.
 */
export default function PayPalConnectedPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [returnTo] = useState(() => takeReturnTo(roleHome.provider));
  // the code is valid only once, StrictMode must not send it twice
  const sent = useRef(false);

  useEffect(() => {
    if (sent.current) {
      return;
    }
    sent.current = true;
    const code = params.get('code');
    const linkState = params.get('state');
    if (!code || !linkState) {
      setState({ kind: 'failed', message: 'Die Verknüpfung wurde bei PayPal abgebrochen.' });
      return;
    }
    void completePayPalLink({ body: { code, state: linkState } }).then(({ data, error }) =>
      setState(data ? { kind: 'linked', account: data } : { kind: 'failed', message: problemMessage(error) }),
    );
  }, [params]);

  return (
    <OnboardingShell>
      {state.kind === 'loading' && (
        <Flex justify="center" style={{ padding: 64 }}>
          <Spin size="large" tip="PayPal-Konto wird verknüpft …">
            <div style={{ width: 200, height: 80 }} />
          </Spin>
        </Flex>
      )}
      {state.kind === 'linked' && (
        <Result
          status="success"
          title="PayPal ist verknüpft."
          subTitle={`Auszahlungen gehen ab jetzt an ${state.account.email ?? 'dein PayPal-Konto'}.`}
          extra={
            <Flex justify="center" gap={10} wrap>
              <Button className="va-accent" size="large" onClick={() => navigate(returnTo, { replace: true })}>
                Weiter
              </Button>
              <Button size="large" onClick={() => navigate(roleHome.provider, { replace: true })}>
                Zur Übersicht
              </Button>
            </Flex>
          }
        />
      )}
      {state.kind === 'failed' && (
        <Result
          status="warning"
          title="PayPal wurde nicht verknüpft."
          subTitle={state.message}
          extra={
            <Button
              className="va-accent"
              size="large"
              onClick={() => navigate(returnTo === roleHome.provider ? PAYOUT_ACCOUNT_PATH : returnTo, { replace: true })}
            >
              Erneut versuchen
            </Button>
          }
        />
      )}
    </OnboardingShell>
  );
}
