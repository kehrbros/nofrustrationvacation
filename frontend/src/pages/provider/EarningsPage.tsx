import { Button, Card, Col, Flex, InputNumber, Row, Select, Table, Tag, Typography, message } from 'antd';
import { useCallback, useEffect, useState } from 'react';

import { DEFAULT_CURRENCY, SUPPORTED_CURRENCIES } from '../../accounting/currencies';
import { entryTypeLabel, formatDate, payoutStatus } from '../../accounting/labels';
import { problemMessage } from '../../accounting/problems';
import { usePayoutAccount } from '../../accounting/usePayoutAccount';
import BalanceHero from '../../components/BalanceHero';
import { formatMoney } from '../../components/formatMoney';
import PageHeader from '../../components/PageHeader';
import PayPalConnectCard from '../../components/PayPalConnectCard';
import { useProviderOnboarding } from '../../onboarding/ProviderOnboardingContext';
import {
  getBalances,
  getEntries,
  getPayouts,
  requestPayout,
  type BalancesDto,
  type LedgerEntryDto,
  type PayoutDto,
} from '../../generated/api';
import { brand } from '../../theme';

/**
 * Earnings of the provider: payout to the linked PayPal account, payouts and bookings.
 */
export default function EarningsPage() {
  const { status, reload: reloadAccount } = usePayoutAccount();
  const onboarding = useProviderOnboarding();
  const [currency, setCurrency] = useState<string>();
  const [balances, setBalances] = useState<BalancesDto>();
  const [payouts, setPayouts] = useState<PayoutDto[]>([]);
  const [entries, setEntries] = useState<LedgerEntryDto[]>([]);
  const [amount, setAmount] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (target?: string) => {
    const query = target ? { query: { currency: target } } : undefined;
    const [balanceResult, payoutResult, entryResult] = await Promise.all([
      getBalances(query),
      getPayouts(),
      getEntries(query),
    ]);
    setBalances(balanceResult.data);
    setCurrency((current) => current ?? balanceResult.data?.earnings.currency ?? DEFAULT_CURRENCY);
    setPayouts(payoutResult.data ?? []);
    setEntries((entryResult.data ?? []).filter((entry) => entry.account === 'EARNINGS'));
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const changeCurrency = (next: string) => {
    setCurrency(next);
    setAmount(null);
    void load(next);
  };

  const payout = async (all: boolean) => {
    if (!currency) return;
    setBusy(true);
    const { data, error } = await requestPayout({ body: { currency, amount: all ? undefined : (amount ?? undefined) } });
    setBusy(false);
    if (!data) {
      void message.error(problemMessage(error));
      return;
    }
    if (data.status === 'FAILED') {
      void message.error(`PayPal hat die Auszahlung abgelehnt. Der Betrag ist wieder auf deinem Konto.`);
    } else {
      void message.success(`${formatMoney(data.paidOut)} sind auf dem Weg zu PayPal.`);
    }
    setAmount(null);
    void load(currency);
  };

  const linked = status?.linked ?? false;
  const nothingToPayOut = !balances || Number(balances.earnings.amount) <= 0;

  return (
    <>
      <PageHeader title="Einnahmen" description="Was deine Angebote eingebracht haben und was du dir auszahlen lässt." />
      <BalanceHero
        greeting="Auszahlbar"
        amount={formatMoney(balances?.earnings)}
        caption="abgerundet auf ganze Cent, Auszahlung über PayPal"
      />
      <Row gutter={[24, 24]}>
        <Col xs={24} lg={12}>
          <Card title="Auszahlen" style={{ height: '100%' }}>
            <Flex vertical gap={16}>
              <Flex gap={10} wrap>
                <InputNumber<string>
                  stringMode
                  min="0"
                  step="1"
                  precision={2}
                  decimalSeparator=","
                  size="large"
                  placeholder="Betrag"
                  value={amount}
                  onChange={setAmount}
                  disabled={!linked}
                  style={{ width: 200 }}
                />
                <Select
                  size="large"
                  value={currency}
                  onChange={changeCurrency}
                  options={SUPPORTED_CURRENCIES.map((value) => ({ value, label: value }))}
                  style={{ width: 110 }}
                  aria-label="Währung"
                />
              </Flex>
              <Flex gap={10} wrap>
                <Button
                  className="va-accent"
                  size="large"
                  loading={busy}
                  disabled={!linked || !amount || Number(amount) <= 0}
                  onClick={() => void payout(false)}
                >
                  Auszahlen
                </Button>
                <Button size="large" disabled={!linked || nothingToPayOut || busy} onClick={() => void payout(true)}>
                  Alles auszahlen
                </Button>
              </Flex>
              <Typography.Text type="secondary">
                Einnahmen werden in USD geführt und zum aktuellen EZB-Kurs umgerechnet. PayPal bearbeitet Auszahlungen
                meist innerhalb weniger Minuten. Mindestbetrag 10 USD.
              </Typography.Text>
            </Flex>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="Auszahlungskonto" style={{ height: '100%' }}>
            <PayPalConnectCard status={status} returnTo="/provider/einnahmen" onChanged={() => void Promise.all([reloadAccount(), onboarding.reload()])}
              compact />
          </Card>
        </Col>
      </Row>
      <Card title="Auszahlungen">
        <Table<PayoutDto>
          rowKey="id"
          dataSource={payouts}
          pagination={false}
          locale={{ emptyText: 'Noch keine Auszahlungen' }}
          columns={[
            { title: 'Angefordert', dataIndex: 'requestedAt', render: (value: string) => formatDate(value) },
            { title: 'Betrag', dataIndex: 'paidOut', render: (_, row) => formatMoney(row.paidOut) },
            {
              title: 'Status',
              dataIndex: 'status',
              render: (_, row) => <Tag color={payoutStatus[row.status].color}>{payoutStatus[row.status].label}</Tag>,
            },
            { title: 'Abgeschlossen', dataIndex: 'completedAt', render: (value?: string | null) => formatDate(value) },
          ]}
        />
      </Card>
      <Card title="Buchungen">
        <Table<LedgerEntryDto>
          rowKey="id"
          dataSource={entries}
          pagination={false}
          locale={{ emptyText: 'Noch keine Buchungen' }}
          columns={[
            { title: 'Datum', dataIndex: 'bookedAt', render: (value: string) => formatDate(value) },
            { title: 'Art', dataIndex: 'type', render: (_, row) => entryTypeLabel[row.type] },
            {
              title: 'Betrag',
              dataIndex: 'amount',
              align: 'right',
              render: (_, row) => (
                <span style={{ fontWeight: 700, color: Number(row.amount.amount) < 0 ? brand.ink : '#237804' }}>
                  {formatMoney(row.amount)}
                </span>
              ),
            },
          ]}
        />
      </Card>
    </>
  );
}
