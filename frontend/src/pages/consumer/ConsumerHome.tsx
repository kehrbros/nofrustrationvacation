import { Button, Card, Empty } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';

import BalanceHero from '../../components/BalanceHero';
import { formatMoney } from '../../components/formatMoney';
import { getBalances, getProfile } from '../../generated/api';
import type { AppUserDto, BalancesDto } from '../../generated/api';

/**
 * Start page of the consumer: credit and the latest agreements.
 */
export default function ConsumerHome() {
  const navigate = useNavigate();
  const [profile, setProfile] = useState<AppUserDto>();
  const [balances, setBalances] = useState<BalancesDto>();

  useEffect(() => {
    void getProfile().then(({ data }) => setProfile(data));
    void getBalances().then(({ data }) => setBalances(data));
  }, []);

  return (
    <>
      <BalanceHero
        greeting={`Hallo${profile?.username ? `, ${profile.username}` : ''}. Lehn dich zurück, dein Agent handelt.`}
        amount={formatMoney(balances?.credit)}
        caption="verfügbares Guthaben"
        actions={
          <>
            <Button className="va-accent" size="large" onClick={() => navigate('/consumer/auftraege/neu')}>
              Tokens kaufen
            </Button>
            <Button size="large" ghost onClick={() => navigate('/consumer/guthaben')}>
              Guthaben aufladen
            </Button>
          </>
        }
      />
      <Card title="Letzte Vereinbarungen">
        <Empty description="Noch keine Vereinbarungen" />
      </Card>
    </>
  );
}
