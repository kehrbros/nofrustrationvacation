import { Button, Card, Col, Flex, InputNumber, Row, Select, Table, Tag, Typography, message } from 'antd';
import { useEffect, useState } from 'react';

import { currencySymbol, DEFAULT_CURRENCY, SUPPORTED_CURRENCIES } from '../../accounting/currencies';
import { formatDate, topUpStatus } from '../../accounting/labels';
import { problemMessage } from '../../accounting/problems';
import BalanceHero from '../../components/BalanceHero';
import { formatMoney } from '../../components/formatMoney';
import PageHeader from '../../components/PageHeader';
import { getBalances, getTopUps, startTopUp, type BalancesDto, type TopUpDto } from '../../generated/api';
import { brand } from '../../theme';

const QUICK_AMOUNTS = ['10', '25', '50', '100'];

/**
 * Consumer tops up platform credit via PayPal. The browser leaves for PayPal and comes back to
 * {@link TopUpReturnPage}.
 */
export default function TopUpPage() {
  const [balances, setBalances] = useState<BalancesDto>();
  const [topUps, setTopUps] = useState<TopUpDto[]>([]);
  const [currency, setCurrency] = useState<string>();
  const [amount, setAmount] = useState<string | null>('25');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void getBalances().then(({ data }) => {
      setBalances(data);
      setCurrency((current) => current ?? data?.credit.currency ?? DEFAULT_CURRENCY);
    });
    void getTopUps().then(({ data }) => setTopUps(data ?? []));
  }, []);

  const pay = async () => {
    if (!amount || !currency) return;
    setBusy(true);
    const { data, error } = await startTopUp({ body: { amount: { amount, currency } } });
    if (!data) {
      setBusy(false);
      void message.error(problemMessage(error));
      return;
    }
    window.location.assign(data.approveUrl);
  };

  const symbol = currencySymbol(currency ?? DEFAULT_CURRENCY);

  return (
    <>
      <PageHeader title="Guthaben" description="Mit Guthaben bezahlt dein Agent die Tokens, die er für dich kauft." />
      <BalanceHero greeting="Verfügbar" amount={formatMoney(balances?.credit)} caption="Guthaben auf VacantAgent" />
      <Row gutter={[24, 24]}>
        <Col xs={24} lg={14}>
          <Card title="Guthaben aufladen">
            <Flex vertical gap={20}>
              <Flex gap={10} wrap role="radiogroup" aria-label="Betrag">
                {QUICK_AMOUNTS.map((value) => {
                  const checked = amount === value;
                  return (
                    <button
                      key={value}
                      type="button"
                      role="radio"
                      aria-checked={checked}
                      onClick={() => setAmount(value)}
                      style={{
                        minWidth: 92,
                        padding: '12px 18px',
                        borderRadius: 999,
                        border: `2px solid ${brand.ink}`,
                        background: checked ? brand.ink : brand.white,
                        color: checked ? brand.white : brand.ink,
                        font: 'inherit',
                        fontSize: 18,
                        fontWeight: 700,
                        cursor: 'pointer',
                      }}
                    >
                      {value} {symbol}
                    </button>
                  );
                })}
              </Flex>
              <Flex gap={10} wrap align="center">
                <InputNumber<string>
                  stringMode
                  min="0"
                  precision={2}
                  decimalSeparator=","
                  size="large"
                  value={amount}
                  onChange={setAmount}
                  aria-label="Eigener Betrag"
                  style={{ width: 200 }}
                />
                <Select
                  size="large"
                  value={currency}
                  onChange={setCurrency}
                  options={SUPPORTED_CURRENCIES.map((value) => ({ value, label: value }))}
                  style={{ width: 110 }}
                  aria-label="Währung"
                />
              </Flex>
              <div>
                <Button
                  className="va-accent"
                  size="large"
                  style={{ height: 52, padding: '0 28px', fontSize: 17 }}
                  loading={busy}
                  disabled={!amount || Number(amount) <= 0}
                  onClick={() => void pay()}
                >
                  Weiter zu PayPal
                </Button>
              </div>
              <Typography.Text type="secondary">
                Du bestätigst die Zahlung bei PayPal und kommst danach hierher zurück. Guthaben wird in USD geführt, die
                Umrechnung erfolgt zum aktuellen EZB-Kurs. Mindestens 5 USD, höchstens 1.000 USD pro Aufladung.
              </Typography.Text>
            </Flex>
          </Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card title="So funktioniert's" style={{ height: '100%' }}>
            <ol style={{ margin: 0, paddingLeft: 20, display: 'flex', flexDirection: 'column', gap: 10, fontSize: 15 }}>
              <li>Betrag wählen und zu PayPal wechseln.</li>
              <li>Bei PayPal anmelden und die Zahlung bestätigen.</li>
              <li>Zurück bei VacantAgent ist das Guthaben sofort verfügbar.</li>
            </ol>
          </Card>
        </Col>
      </Row>
      <Card title="Letzte Aufladungen">
        <Table<TopUpDto>
          rowKey="id"
          dataSource={topUps}
          pagination={false}
          locale={{ emptyText: 'Noch keine Aufladungen' }}
          columns={[
            { title: 'Datum', dataIndex: 'createdAt', render: (value: string) => formatDate(value) },
            { title: 'Bezahlt', dataIndex: 'paid', render: (_, row) => formatMoney(row.paid) },
            { title: 'Gutgeschrieben', dataIndex: 'credited', render: (_, row) => formatMoney(row.credited) },
            {
              title: 'Status',
              dataIndex: 'status',
              render: (_, row) => <Tag color={topUpStatus[row.status].color}>{topUpStatus[row.status].label}</Tag>,
            },
          ]}
        />
      </Card>
    </>
  );
}
