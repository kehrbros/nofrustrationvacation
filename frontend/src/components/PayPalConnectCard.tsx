import { CheckCircleFilled, SafetyCertificateOutlined, SwapOutlined, WalletOutlined } from '@ant-design/icons';
import { Alert, Button, Flex, Popconfirm, Tag, Typography, message } from 'antd';
import { useState, type ReactNode } from 'react';

import { connectPayPal } from '../accounting/payPalLink';
import { problemMessage } from '../accounting/problems';
import { unlinkPayoutAccount, type PayoutAccountStatusDto } from '../generated/api';
import { brand } from '../theme';

interface PayPalConnectCardProps {
  status?: PayoutAccountStatusDto;
  /** Page to continue at after PayPal returned. */
  returnTo: string;
  /** Called after unlinking. */
  onChanged?: () => void;
  /** Hides the explanation, for pages that already explain it. */
  compact?: boolean;
}

const benefits: { icon: ReactNode; text: string }[] = [
  { icon: <WalletOutlined />, text: 'Deine Einnahmen landen direkt auf deinem PayPal-Konto.' },
  { icon: <SafetyCertificateOutlined />, text: 'Du meldest dich bei PayPal an. Dein Passwort sehen wir nie.' },
  { icon: <SwapOutlined />, text: 'Du kannst das Konto jederzeit wechseln.' },
];

/**
 * Links the PayPal account a provider gets paid out to ("Log in with PayPal"), or shows the linked one.
 */
export default function PayPalConnectCard({ status, returnTo, onChanged, compact }: PayPalConnectCardProps) {
  const [busy, setBusy] = useState(false);

  const connect = async () => {
    setBusy(true);
    const error = await connectPayPal(returnTo);
    if (error) {
      setBusy(false);
      void message.error(problemMessage(error));
    }
  };

  const unlink = async () => {
    const { error } = await unlinkPayoutAccount();
    if (error) {
      void message.error(problemMessage(error));
    } else {
      void message.success('PayPal-Konto getrennt.');
      onChanged?.();
    }
  };

  if (status?.linked) {
    return (
      <section style={{ border: `2px solid ${brand.ink}`, borderRadius: 16, padding: 24 }}>
        <Flex justify="space-between" align="center" wrap gap={16}>
          <Flex align="center" gap={14}>
            <CheckCircleFilled style={{ fontSize: 32, color: brand.accent }} />
            <div>
              <div style={{ fontSize: 13, color: brand.muted }}>Auszahlungen gehen an</div>
              <div style={{ fontSize: 18, fontWeight: 700 }}>{status.email ?? 'dein PayPal-Konto'}</div>
              <Flex gap={6} style={{ marginTop: 4 }}>
                <Tag bordered={false} color="default">
                  PayPal
                </Tag>
                {status.verified ? (
                  <Tag bordered={false} color="success">
                    verifiziert
                  </Tag>
                ) : (
                  <Tag bordered={false} color="warning">
                    nicht verifiziert
                  </Tag>
                )}
              </Flex>
            </div>
          </Flex>
          <Flex gap={8} wrap>
            <Button onClick={connect} loading={busy} disabled={!status.payPalAvailable}>
              Anderes Konto
            </Button>
            <Popconfirm
              title="PayPal-Konto trennen?"
              description="Ohne Auszahlungskonto kannst du keine neuen Angebote einstellen."
              okText="Trennen"
              cancelText="Abbrechen"
              onConfirm={unlink}
            >
              <Button type="text">Trennen</Button>
            </Popconfirm>
          </Flex>
        </Flex>
      </section>
    );
  }

  return (
    <section
      style={{
        background: brand.ink,
        color: brand.white,
        borderRadius: 20,
        padding: 32,
        display: 'flex',
        flexDirection: 'column',
        gap: 20,
      }}
    >
      {!compact && (
        <div>
          <Typography.Title level={2} style={{ color: brand.white, margin: 0, fontWeight: 800 }}>
            PayPal verknüpfen
          </Typography.Title>
          <div style={{ color: '#c8c8c8', fontSize: 16, marginTop: 6 }}>
            Bevor du Kapazität anbietest, brauchen wir ein Konto für deine Auszahlungen.
          </div>
        </div>
      )}
      <Flex vertical gap={10}>
        {benefits.map((benefit) => (
          <Flex key={benefit.text} gap={12} align="center" style={{ fontSize: 15 }}>
            <span style={{ color: brand.accent, fontSize: 20 }}>{benefit.icon}</span>
            {benefit.text}
          </Flex>
        ))}
      </Flex>
      {status && !status.payPalAvailable && (
        <Alert type="warning" showIcon message="PayPal ist auf dem Server noch nicht eingerichtet." />
      )}
      <div>
        <Button
          className="va-accent"
          size="large"
          style={{ height: 52, padding: '0 28px', fontSize: 17 }}
          loading={busy}
          disabled={!status?.payPalAvailable}
          onClick={connect}
        >
          Mit PayPal verbinden
        </Button>
      </div>
    </section>
  );
}
