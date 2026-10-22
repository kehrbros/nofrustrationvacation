import { Button, Flex, Result, Spin } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';

import { problemMessage } from '../../accounting/problems';
import { formatMoney } from '../../components/formatMoney';
import { cancelTopUp, captureTopUp, type TopUpDto } from '../../generated/api';

type State = { kind: 'loading' } | { kind: 'done'; topUp: TopUpDto } | { kind: 'failed'; message: string };

/**
 * Return and cancel URL of PayPal payments: PayPal appends the order id as {@code token}. Captures the payment,
 * or records the cancellation ({@code abgebrochen=1}).
 */
export default function TopUpReturnPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [state, setState] = useState<State>({ kind: 'loading' });
  const sent = useRef(false);

  useEffect(() => {
    if (sent.current) {
      return;
    }
    sent.current = true;
    const orderId = params.get('token');
    if (!orderId) {
      setState({ kind: 'failed', message: 'PayPal hat keine Zahlung zurückgemeldet.' });
      return;
    }
    const request = params.has('abgebrochen') ? cancelTopUp({ path: { orderId } }) : captureTopUp({ path: { orderId } });
    void request.then(({ data, error }) =>
      setState(data ? { kind: 'done', topUp: data as TopUpDto } : { kind: 'failed', message: problemMessage(error) }),
    );
  }, [params]);

  const back = (
    <Flex justify="center" gap={10} wrap>
      <Button className="va-accent" size="large" onClick={() => navigate('/consumer', { replace: true })}>
        Zur Übersicht
      </Button>
      <Button size="large" onClick={() => navigate('/consumer/guthaben', { replace: true })}>
        Guthaben
      </Button>
    </Flex>
  );

  if (state.kind === 'loading') {
    return (
      <Flex justify="center" style={{ padding: 64 }}>
        <Spin size="large" tip="Zahlung wird abgeschlossen …">
          <div style={{ width: 200, height: 80 }} />
        </Spin>
      </Flex>
    );
  }
  if (state.kind === 'failed') {
    return <Result status="error" title="Aufladung fehlgeschlagen" subTitle={state.message} extra={back} />;
  }
  const { topUp } = state;
  switch (topUp.status) {
    case 'COMPLETED':
      return (
        <Result
          status="success"
          title={`${formatMoney(topUp.credited)} gutgeschrieben.`}
          subTitle={`Bezahlt mit PayPal: ${formatMoney(topUp.paid)}`}
          extra={back}
        />
      );
    case 'PENDING':
      return (
        <Result
          status="info"
          title="PayPal prüft die Zahlung."
          subTitle="Sobald PayPal sie freigibt, schreiben wir das Guthaben automatisch gut."
          extra={back}
        />
      );
    case 'CANCELLED':
      return <Result status="warning" title="Zahlung abgebrochen" subTitle="Es wurde nichts abgebucht." extra={back} />;
    default:
      return <Result status="error" title="Aufladung fehlgeschlagen" subTitle={topUp.failureReason ?? undefined} extra={back} />;
  }
}
