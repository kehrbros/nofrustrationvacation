import { Button, Card, Empty, Flex, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';

import { formatMoney } from '../../components/formatMoney';
import { getProfile } from '../../generated/api';
import type { AppUserDto } from '../../generated/api';
import { brand } from '../../theme';

/**
 * Start page of the consumer: account balance and the latest agreements.
 */
export default function ConsumerHome() {
  const navigate = useNavigate();
  const [profile, setProfile] = useState<AppUserDto>();

  useEffect(() => {
    void getProfile().then(({ data }) => setProfile(data));
  }, []);

  return (
    <>
      <section
        style={{
          background: brand.ink,
          color: brand.white,
          borderRadius: 16,
          padding: 32,
          display: 'flex',
          flexWrap: 'wrap',
          justifyContent: 'space-between',
          alignItems: 'flex-end',
          gap: 24,
        }}
      >
        <div>
          <div style={{ fontSize: 15, color: '#c8c8c8' }}>
            Hallo{profile?.username ? `, ${profile.username}` : ''}. Lehn dich zurück, dein Agent handelt.
          </div>
          <Typography.Title
            level={1}
            style={{ color: brand.white, margin: '8px 0 0', fontSize: 52, fontWeight: 800, letterSpacing: '-0.02em' }}
          >
            {formatMoney(profile?.accountBalance) ?? '–'}
          </Typography.Title>
          <div style={{ fontSize: 14, color: '#c8c8c8' }}>verfügbares Guthaben</div>
        </div>
        <Flex gap={10} wrap>
          <Button className="va-accent" size="large" onClick={() => navigate('/consumer/auftraege/neu')}>
            Tokens kaufen
          </Button>
          <Button size="large" ghost onClick={() => navigate('/marktplatz')}>
            Marktplatz ansehen
          </Button>
        </Flex>
      </section>
      <Card title="Letzte Vereinbarungen">
        <Empty description="Noch keine Vereinbarungen" />
      </Card>
    </>
  );
}
